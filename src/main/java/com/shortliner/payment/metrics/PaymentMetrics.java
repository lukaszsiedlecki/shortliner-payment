package com.shortliner.payment.metrics;

import com.shortliner.payment.payment.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The pair to compare in Grafana is payment_charge_attempts_total vs
 * payment_idempotency_keys_total: attempts counts every call into
 * PaymentService.charge(), including replays of an existing key, while
 * idempotency_keys counts only genuinely new keys. Attempts running ahead of
 * keys is duplicate requests being correctly absorbed, not double charges.
 */
@Component
public class PaymentMetrics {

    private final Counter chargeAttemptsCounter;
    private final Counter idempotencyKeysCounter;
    private final Counter successCounter;
    private final Counter failedCounter;
    private final Counter reconciliationResolvedCounter;
    private final Counter outboxPublishedCounter;

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
        this.outboxPublishedCounter = Counter.builder("outbox.events.published")
                .description("Outbox events published to Kafka")
                .register(registry);
    }

    public void chargeAttempted() {
        chargeAttemptsCounter.increment();
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
}
