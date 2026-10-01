package com.shortliner.payment.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka payload published on the {@code shortliner.payments.completed} topic
 * once a payment reaches SUCCESS. A consumer will use {@code userId} (the
 * Keycloak subject) to grant the premium plan to that user.
 * {@code idempotencyKey} is only unique per user.
 */
public record PaymentCompletedEvent(
        UUID paymentId,
        String userId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        Instant completedAt
) {
}
