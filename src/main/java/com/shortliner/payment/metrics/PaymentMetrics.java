package com.shortliner.payment.metrics;

import com.shortliner.payment.payment.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The pair to compare in Grafana is payment_charge_attempts_total vs
 * payment_idempotency_keys_total: attempts counts every call into
 * PaymentService.charge(), including replays of an existing key, while
 * idempotency_keys counts only genuinely new keys. Attempts running ahead of
 * keys is duplicate requests being correctly absorbed, not double charges.
 * <p>
 * The per-outcome breakdown of actual provider calls lives on the
 * shortliner.payment.charge timer instead of a tag on the attempts counter:
 * replays never reach the provider, so they have no success/declined/error
 * outcome to tag, and tagging only some attempts would break the comparison
 * above.
 */
@Component
public class PaymentMetrics {

    private final Counter chargeAttemptsCounter;
    private final Counter idempotencyKeysCounter;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter reconciliationResolvedCounter;
    private final Counter outboxPublishedCounter;
    private final Counter outboxPublishFailuresCounter;
    private final Map<ChargeOutcome, Timer> chargeTimers = new EnumMap<>(ChargeOutcome.class);

    public PaymentMetrics(MeterRegistry registry) {
        this.chargeAttemptsCounter = Counter.builder("payment.charge.attempts")
                .description("Every call into PaymentService.charge(), including idempotent replays of an existing idempotency key")
                .register(registry);
        this.idempotencyKeysCounter = Counter.builder("payment.idempotency.keys")
                .description("Distinct idempotency keys that resulted in a newly created payment row")
                .register(registry);
        this.successCounter = Counter.builder("payment.status")
                .tag("status", "SUCCESS")
                .description("Payments by final status")
                .register(registry);
        this.failedCounter = Counter.builder("payment.status")
                .tag("status", "FAILED")
                .description("Payments by final status")
                .register(registry);
        this.reconciliationResolvedCounter = Counter.builder("payment.reconciliation.resolved")
                .description("Stuck PENDING payments resolved by the reconciliation job")
                .register(registry);
        // Name kept as-is: the homelab Grafana dashboard queries
        // outbox_events_published_total. Failures get their own counter
        // rather than a result tag here, so that query keeps meaning
        // "successfully published" without needing a label filter.
        this.outboxPublishedCounter = Counter.builder("outbox.events.published")
                .description("Outbox events published to Kafka")
                .register(registry);
        this.outboxPublishFailuresCounter = Counter.builder("shortliner.payment.outbox.publish.failures")
                .description("Outbox events whose Kafka send failed or timed out; the row stays PENDING and is retried")
                .register(registry);
        for (ChargeOutcome outcome : ChargeOutcome.values()) {
            chargeTimers.put(outcome, Timer.builder("shortliner.payment.charge")
                    .tag("result", outcome.tagValue())
                    .description("Latency of charge calls to the payment provider, by outcome")
                    .publishPercentileHistogram()
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(registry));
        }
    }

    public void chargeAttempted() {
        chargeAttemptsCounter.increment();
    }

    public void chargeCompleted(ChargeOutcome outcome, Duration duration) {
        chargeTimers.get(outcome).record(duration);
    }

    public void idempotencyKeySeen() {
        idempotencyKeysCounter.increment();
    }

    public void statusRecorded(PaymentStatus status) {
        if (status == PaymentStatus.SUCCESS) {
            successCounter.increment();
        } else if (status == PaymentStatus.FAILED) {
            failedCounter.increment();
        }
    }

    public void reconciliationResolved() {
        reconciliationResolvedCounter.increment();
    }

    public void outboxEventPublished() {
        outboxPublishedCounter.increment();
    }

    public void outboxPublishFailed() {
        outboxPublishFailuresCounter.increment();
    }
}
