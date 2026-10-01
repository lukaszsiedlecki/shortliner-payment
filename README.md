# Shortliner Payment

Learning microservice: handles payments for a premium plan of the Shortliner URL shortener. It's a
deliberately small, standalone playground for six architectural patterns around safe, idempotent,
multi-instance payment processing — see [Architecture](#architecture) below for where each one lives.

## Tech Stack

- Java 25
- Spring Boot 3.5
- Spring Data JPA + Hibernate
- PostgreSQL 17
- Apache Kafka
- Flyway (database migrations)
- SpringDoc OpenAPI (Swagger UI)
- Docker

## Getting Started

### Prerequisites

- Java 25+
- Docker
- Access to a PostgreSQL instance and (optionally) Kafka

### Configuration

Copy the example below into a `.env` file in the project root:

```env
# Server
SERVER_PORT=8083

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=shortliner_payment
DB_USERNAME=postgres
DB_PASSWORD=postgres
HIKARI_MAX_POOL_SIZE=20
HIKARI_MIN_IDLE=5

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Auth (Keycloak realm "shortliner") — in the cluster, the JWKS URI must be
# the in-cluster service (keycloak.local doesn't resolve from pods)
KEYCLOAK_JWK_SET_URI=http://keycloak.local/realms/shortliner/protocol/openid-connect/certs
KEYCLOAK_ISSUER_URI=http://keycloak.local/realms/shortliner

# Reconciliation
RECONCILIATION_INTERVAL_MS=30000
RECONCILIATION_STUCK_AFTER_MS=20000

# Outbox
OUTBOX_POLL_INTERVAL_MS=2000
OUTBOX_BATCH_SIZE=50

# Mock payment provider
MOCK_PROVIDER_MIN_LATENCY_MS=50
MOCK_PROVIDER_MAX_LATENCY_MS=200
MOCK_PROVIDER_FAILURE_RATE=0.1

# Debug endpoint (see below) — disable outside local exploration
PAYMENT_DEBUG_ENABLED=true

# Logging
LOG_LEVEL_APP=DEBUG
LOG_LEVEL_KAFKA=INFO

# Observability
TRACING_SAMPLING_PROBABILITY=1.0
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
OTEL_TRACING_EXPORT_ENABLED=false
LOGGING_STRUCTURED_FORMAT_CONSOLE=
```

### Run Locally

```bash
./gradlew bootRun
```

### Run with Docker

```bash
docker-compose up --build
```

The service joins the external `shortliner-net` Docker network, so PostgreSQL and Kafka should be
reachable from that network.

## Database Migrations

Schema is managed by Flyway. Migrations run automatically on startup. Scripts live in:

```
src/main/resources/db/migration/
  V1__create_payments_table.sql
  V2__create_outbox_events_table.sql
```

Hibernate is set to `validate` mode — it verifies entities match the schema but never modifies it.

## Architecture

Six patterns, deliberately kept in separate, identifiable places:

| # | Pattern | Where |
|---|---|---|
| 1 | **Idempotent Receiver** | `(user_id, idempotency_key)` has a DB `UNIQUE` constraint (keys are scoped per user) — the actual safeguard, not app-level checking. `PaymentTransactionalOperations.getOrCreatePending` attempts an insert; on conflict it reads back whichever request won the race. |
| 2 | **Explicit state machine** | `PaymentStatus` (PENDING → SUCCESS / FAILED) with `canTransitionTo`, enforced on every write in `PaymentTransactionalOperations.finalizePayment`. |
| 3 | **Optimistic locking** | `@Version` on `Payment`. Two callers racing to finalize the same row (the direct charge path and the reconciliation job) — one wins, the other gets `ObjectOptimisticLockingFailureException` and reads the winner's result instead. |
| 4 | **Transactional Outbox** | `OutboxEvent`, written in the *same* DB transaction as the SUCCESS status update (`PaymentTransactionalOperations.finalizePayment`). `OutboxPublisher` polls and publishes to Kafka separately, so an event can never be lost or published for an uncommitted charge. |
| 5 | **Reconciliation job** | `ReconciliationJob` sweeps for payments stuck PENDING past a threshold and resolves them via `PaymentProvider.checkStatus`, simulating a crash between charging and persisting. |
| 6 | **Multi-instance safety** | Falls out of 1+3+4: nothing lives in JVM memory except the mock provider's own internal ledger (which exists only to mimic a *real* gateway's idempotency, not to provide ours — see its Javadoc). Any replica can safely handle any request or reconciliation sweep. |

### Package layout

```
com.shortliner.payment
├── payment/            Core domain: entity, state machine, DTOs, controller, service
│   └── service/        PaymentService (orchestrator) + PaymentTransactionalOperations
│                        (the actual @Transactional boundaries — kept in a separate bean
│                        because self-invocation silently skips @Transactional; see its Javadoc)
├── provider/            PaymentProvider interface + MockPaymentProvider
├── outbox/               OutboxEvent, OutboxPublisher (FOR UPDATE SKIP LOCKED), Kafka payload
├── reconciliation/       ReconciliationJob
└── metrics/              PaymentMetrics
```

### A subtlety worth knowing if you extend this

Two DB-level races matter here, and they fail differently on Postgres:

- A **unique-constraint violation** (idempotency key conflict) aborts the current Postgres
  transaction outright — every further statement on it fails until rollback. The insert attempt
  therefore runs in its own transaction, separate from any fallback read.
- An **optimistic-lock loss** is just an `UPDATE ... WHERE version = ?` matching zero rows — not a
  Postgres-level error — but per the JPA spec it still marks the *persistence context* (and any
  transaction sharing it) for rollback. Catching the exception inside the same `@Transactional`
  method that threw it doesn't help; the method has to be allowed to fail and roll back completely,
  with the fallback read happening one level up, in a fresh transaction.

Both `PaymentTransactionalOperations` methods and `PaymentService.finalizePayment` are structured
around this. `PaymentConcurrencyIT` (Testcontainers, real Postgres) is what actually caught the
second one during development — H2 doesn't reproduce it.

## Kafka

On a successful charge, a `PaymentCompleted` event is published to `shortliner.payments.completed`:

```json
{
  "paymentId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "f3c1a2b4-1111-2222-3333-444455556666",
  "idempotencyKey": "client-generated-key",
  "amount": 9.99,
  "currency": "USD",
  "completedAt": "2026-07-28T12:30:00Z"
}
```

`userId` is the Keycloak subject of the payer; a consumer is expected to use it to unlock the
premium plan for that user. `idempotencyKey` is only unique per user.

## API Reference

Base URL: `http://localhost:8083`

Every `/api/payments/**` endpoint requires `Authorization: Bearer <Keycloak access token>` (401
otherwise). In the deployed system, `shortliner-gateway` relays the token; the caller's identity is
always the token's `sub`, never anything in the request.

| Endpoint | Anonymous | User | Admin |
|---|---|---|---|
| `POST /api/payments` | 401 | charges as themselves | same |
| `GET /api/payments` | 401 | own payments, newest first, paged | own payments |
| `GET /api/payments/{id}` | 401 | own only — 404 otherwise | any |
| `POST /api/payments/_debug/**` | 401 | 403 | allowed (if `PAYMENT_DEBUG_ENABLED`) |
| `/actuator/health/**`, `/actuator/prometheus`, Swagger | allowed | allowed | allowed |
| other `/actuator/**` | 401 | 403 | allowed |

### Create / Charge a Payment

Idempotent: the same user retrying with the same `Idempotency-Key` gets the existing payment
(PENDING or terminal) instead of charging again. Keys are scoped per user — the same key from two
different users creates two independent payments.

```
POST /api/payments
Idempotency-Key: <client-generated key>
```

```bash
curl -s -X POST http://localhost:8083/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 9.99, "currency": "USD"}' | jq
```

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "idempotencyKey": "...",
  "amount": 9.99,
  "currency": "USD",
  "status": "SUCCESS",
  "failureReason": null,
  "createdAt": "2026-07-28T12:30:00Z",
  "updatedAt": "2026-07-28T12:30:00Z"
}
```

### Get a Payment

```
GET /api/payments/{id}
```

Owner or admin only; another user's payment is a 404, so IDs can't be probed for existence.

### List My Payments

```
GET /api/payments?page=0&size=20      # size 1..100
```

```json
{ "content": [ { "id": "...", "status": "SUCCESS", ... } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 } }
```

### Exercising the reconciliation job by hand

`POST /api/payments/_debug/simulate-stuck-payment` (admin role required; disabled via
`PAYMENT_DEBUG_ENABLED=false` outside local exploration) creates a PENDING payment and seeds the mock provider as if the charge
had already succeeded there, without ever finalizing it — simulating this service crashing between
charging and persisting. `GET` the returned id immediately (PENDING), wait past
`RECONCILIATION_STUCK_AFTER_MS`, `GET` it again (SUCCESS).

## Swagger UI

```
http://localhost:8083/swagger-ui.html
http://localhost:8083/api-docs
```

## Observability

### Metrics (Prometheus)

```
http://localhost:8083/actuator/prometheus
```

| Metric | Type | Description |
|---|---|---|
| `payment_charge_attempts_total` | counter | Every call into the charge flow, including replays of an existing idempotency key |
| `payment_idempotency_keys_total` | counter | Distinct idempotency keys that actually created a new payment row |
| `payment_status_total{status=SUCCESS\|FAILED}` | counter | Payments by final status |
| `payment_reconciliation_resolved_total` | counter | Stuck PENDING payments resolved by the reconciliation job |
| `outbox_events_published_total` | counter | Outbox events published to Kafka |

**To spot duplicates in Grafana**: graph `payment_charge_attempts_total` against
`payment_idempotency_keys_total`. Attempts running ahead of distinct keys means duplicate requests
are landing and being correctly absorbed — not double-charged. Compare `payment_idempotency_keys_total`
against `payment_status_total{status="SUCCESS"}` to see charges vs. distinct customer intents.

All metrics carry an `application=shortliner-payment` tag, same as the sibling services, so they
sit in the same shared Grafana dashboard.

Other actuator endpoints: `/actuator/health` (liveness/readiness probes), `/actuator/metrics`.

### Tracing & Logs

Same setup as `shortliner` / `shortliner-analytics`: Micrometer Tracing with the OTel bridge,
OTLP export opt-in via `OTEL_TRACING_EXPORT_ENABLED`, trace/span IDs in every log line via MDC,
structured JSON logs opt-in via `LOGGING_STRUCTURED_FORMAT_CONSOLE`.
