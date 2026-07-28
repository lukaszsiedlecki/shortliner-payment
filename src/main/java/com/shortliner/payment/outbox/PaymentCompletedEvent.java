package com.shortliner.payment.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka payload published on the {@code shortliner.payments.completed} topic
 * once a payment reaches SUCCESS. shortliner will consume this to unlock the
 * premium plan for the user.
 */
public record PaymentCompletedEvent(
        UUID paymentId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        Instant completedAt
) {
}
