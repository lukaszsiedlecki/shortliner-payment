package com.shortliner.payment.payment.service;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.exception.PaymentNotFoundException;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.provider.ChargeResult;
import com.shortliner.payment.provider.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public entry point for the charge flow. Deliberately not @Transactional
 * itself: step 2 below calls an external provider, and a DB transaction must
 * never span a network call. The two steps that do need a transaction each
 * live on {@link PaymentTransactionalOperations}, a separate bean — see its
 * Javadoc for why that separation matters.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactionalOperations transactionalOperations;
    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentMetrics metrics;

    public PaymentService(PaymentTransactionalOperations transactionalOperations,
                           PaymentRepository paymentRepository,
                           PaymentProvider paymentProvider,
                           PaymentMetrics metrics) {
        this.transactionalOperations = transactionalOperations;
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.metrics = metrics;
    }

    public Payment charge(String idempotencyKey, BigDecimal amount, String currency) {
        metrics.chargeAttempted();

        Payment payment = transactionalOperations.getOrCreatePending(idempotencyKey, amount, currency);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            // Idempotent replay: a previous attempt (maybe on another
            // replica) already ran this to completion. Nothing to charge.
            log.debug("Idempotency key {} already resolved to {}, skipping charge", idempotencyKey, payment.getStatus());
            return payment;
        }

        ChargeResult result = paymentProvider.charge(idempotencyKey, amount, currency);
        return finalizePayment(payment.getId(), result);
    }

    public Payment getById(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * Used by the reconciliation job to resolve a payment that's been stuck
     * PENDING, once it has learned the real outcome from the provider.
     * <p>
     * Catches the optimistic-lock loss here, one level up from where it's
     * thrown: {@link PaymentTransactionalOperations#finalizePayment} must be
     * allowed to fail and roll back completely on its own before this reads
     * the winning row in a fresh transaction. Catching it inside that same
     * transactional method would still leave it marked rollback-only.
     */
    public Payment finalizePayment(UUID paymentId, ChargeResult result) {
        try {
            return transactionalOperations.finalizePayment(paymentId, result);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.debug("Payment {} was finalized concurrently, using the winning version", paymentId);
            return paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        }
    }
}
