package com.shortliner.payment.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.outbox.PaymentCompletedEvent;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.exception.InvalidStateTransitionException;
import com.shortliner.payment.payment.exception.PaymentNotFoundException;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.provider.ChargeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Holds every method that needs its own well-defined DB transaction
 * boundary. Kept as a separate bean from {@link PaymentService} — not just
 * for tidiness — because Spring's declarative @Transactional is proxy-based:
 * it only applies when a call arrives through the bean's proxy. If these
 * methods lived on PaymentService and PaymentService called them on itself
 * (e.g. {@code this.finalizePayment(...)}), that self-invocation would
 * bypass the proxy and silently run with no transaction at all. Calling them
 * on this separate, injected bean guarantees the annotations actually apply.
 */
@Component
class PaymentTransactionalOperations {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactionalOperations.class);

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentMetrics metrics;
    private final ObjectMapper objectMapper;

    PaymentTransactionalOperations(PaymentRepository paymentRepository,
                                    OutboxEventRepository outboxEventRepository,
                                    PaymentMetrics metrics,
                                    ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * Idempotent receiver: attempts an insert, and on a unique-constraint
     * conflict reads back whatever row won the race instead of charging
     * again. Deliberately NOT @Transactional — {@code saveAndFlush} and
     * {@code findByIdempotencyKey} each get their own transaction from
     * Spring Data's repository proxy, which is exactly what's wanted here.
     * On Postgres, a unique-constraint violation aborts the current
     * transaction; if a single transaction spanned both the failed insert
     * and the fallback read, the read would fail too. Keeping this method
     * transaction-free (and never letting a caller wrap it in one) is what
     * keeps the two DB round-trips properly isolated from each other.
     */
    Payment getOrCreatePending(String idempotencyKey, BigDecimal amount, String currency) {
        try {
            Payment payment = paymentRepository.saveAndFlush(new Payment(idempotencyKey, amount, currency));
            metrics.idempotencyKeySeen();
            return payment;
        } catch (DataIntegrityViolationException e) {
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    // Key deliberately left out of the message: it'd end up in
                    // the ERROR log line GlobalExceptionHandler writes.
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key hit a unique violation but no row was found", e));
        }
    }

    /**
     * Applies a provider result to a PENDING payment: validates the state
     * transition, writes the terminal status, and — only on SUCCESS — an
     * outbox event, all in one commit (transactional outbox). Reused by both
     * the direct charge path and the reconciliation job, so it has to cope
     * with two callers racing to finalize the same row; the optimistic-lock
     * catch below handles that.
     */
    @Transactional
    Payment finalizePayment(UUID paymentId, ChargeResult result) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.atDebug()
                    .addKeyValue("paymentId", paymentId)
                    .addKeyValue("status", payment.getStatus())
                    .log("Payment already finalized, nothing to do");
            return payment;
        }

        PaymentStatus target = result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        if (!payment.getStatus().canTransitionTo(target)) {
            throw new InvalidStateTransitionException(payment.getStatus(), target);
        }

        payment.setStatus(target);
        payment.setProviderReference(result.providerReference());
        payment.setFailureReason(result.failureReason());
        payment.setUpdatedAt(Instant.now());
        metrics.statusRecorded(target);

        if (target == PaymentStatus.SUCCESS) {
            outboxEventRepository.save(newPaymentCompletedEvent(payment));
        }

        // Deliberately let ObjectOptimisticLockingFailureException propagate
        // instead of catching it here: per the JPA spec, a failed flush
        // marks the persistence context (and this transaction) for
        // rollback, so any further operation sharing it — including a
        // fallback read — would fail with "marked as rollback-only" even
        // though we caught the exception. The caller (PaymentService) reads
        // the winning row afterward, in its own fresh transaction, once
        // this one has actually finished rolling back. Same underlying
        // rule as getOrCreatePending's REQUIRES_NEW-free design: an attempt
        // that might lose a DB-level race, and the fallback read of
        // whoever won it, must never share a transaction.
        return paymentRepository.saveAndFlush(payment);
    }

    private OutboxEvent newPaymentCompletedEvent(Payment payment) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getUpdatedAt());
        try {
            return new OutboxEvent(payment.getId(), "PaymentCompleted", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize PaymentCompletedEvent for payment " + payment.getId(), e);
        }
    }
}
