# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.shortliner.payment.payment.service.PaymentServiceTest"

# Run only the Testcontainers concurrency IT (needs Docker)
./gradlew test --tests "com.shortliner.payment.PaymentConcurrencyIT"

# Build
./gradlew build

# Run with Docker (requires .env file and external shortliner-net network)
docker-compose up --build
```

Most tests use H2 in-memory via the `test` Spring profile (`src/test/resources/application-test.properties`,
H2 running in `MODE=PostgreSQL` compatibility so Flyway's `gen_random_uuid()` migrations apply).
Unlike `shortliner-analytics`, Kafka autoconfiguration is **not** excluded in tests — `OutboxPublisher`
needs a real `KafkaTemplate` bean to construct, though nothing sends through it outside
`PaymentConcurrencyIT` (which mocks it instead). `PaymentConcurrencyIT` runs against a real
Testcontainers Postgres, not H2 — see "A subtlety worth knowing" in README.md for why.

Running locally requires PostgreSQL and (optionally, for Kafka publishing) Kafka. Copy the env vars
from the README into a `.env` file; the minimum required are `DB_HOST`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`.

## Architecture

Learning microservice in the Shortliner system: handles premium-plan payments, reusing the existing
Kafka/Prometheus/Grafana stack. Full pattern-by-pattern breakdown is in README.md's Architecture
section — this is the condensed version:

**Charge flow** (`PaymentController` → `PaymentService` → `PaymentTransactionalOperations`):
1. `getOrCreatePending` attempts an insert; a unique-constraint conflict on `idempotency_key` means
   another request (any replica) already owns this key, so it reads that row back instead.
2. If the row is fresh PENDING, `PaymentProvider.charge` (mocked) is called *outside* any DB
   transaction — a DB transaction must never span an external call.
3. `finalizePayment` validates the state transition, writes the terminal status, and — only on
   SUCCESS — an `OutboxEvent`, all in one commit (transactional outbox). `@Version` guards it against
   a concurrent finalize (e.g. the reconciliation job resolving the same row at the same time).

**`PaymentTransactionalOperations` is a separate bean from `PaymentService` on purpose**: Spring's
`@Transactional` is proxy-based and self-invocation (`this.someMethod()`) silently bypasses it. Every
`@Transactional` method here is called cross-bean so the annotation actually applies. See its Javadoc.

**Reconciliation** (`ReconciliationJob`, `@Scheduled`): finds PENDING rows older than
`payment.reconciliation.stuck-after-ms`, asks the mock provider what really happened via
`checkStatus`, and resolves them through the same `finalizePayment` path. `DebugController`
(`/api/payments/_debug/simulate-stuck-payment`, gated by `payment.debug.enabled`) exists purely to
reproduce this scenario by hand.

**Outbox publishing** (`OutboxPublisher`, `@Scheduled`): polls PENDING outbox rows with a native
`FOR UPDATE SKIP LOCKED` query, so multiple replicas' schedulers never publish the same event twice
and never block on each other. Each send's broker ack is awaited (one shared deadline per batch,
`payment.outbox.send-timeout-ms`) before its row is marked PUBLISHED; unacked rows stay PENDING and
are retried (at-least-once). `OutboxBacklogMetrics` exposes pending-count / oldest-age gauges from a
cached snapshot refreshed on its own schedule, so they keep moving even while the publisher is stuck.

**Spring's default `@Scheduled` pool is a single thread** shared by every scheduled method in the
app — `spring.task.scheduling.pool.size=3` (one per scheduled job) is set explicitly so
`OutboxPublisher` blocking on a slow/unreachable Kafka broker can't starve `ReconciliationJob` or
`OutboxBacklogMetrics` of ever running. Found this by
actually running the app against a broker-less environment during development, not by inspection.

**Mock provider** (`MockPaymentProvider`): keeps its own in-memory ledger keyed by idempotency key,
but only to mimic what a *real* gateway does internally (same key → same result). It is not this
service's idempotency safeguard — that's the DB unique constraint. `seedResult` is a deliberate
test/demo seam for reproducing the "charged but crashed before persisting" scenario without a real
crash.

## Testing

`PaymentStatusTest`, `PaymentServiceTest`, `ReconciliationJobTest`, `MockPaymentProviderTest` are
plain Mockito unit tests, no Spring context. `PaymentConcurrencyIT` is the one that actually proves
the safety claims: N concurrent HTTP requests with the same idempotency key against a real embedded
server + real Postgres must produce exactly one payment row, and two concurrent `OutboxPublisher`
runs must never send the same event twice.
