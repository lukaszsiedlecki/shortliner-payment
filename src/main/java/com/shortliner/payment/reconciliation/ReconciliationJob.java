package com.shortliner.payment.reconciliation;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.payment.service.PaymentService;
import com.shortliner.payment.provider.ChargeResult;
import com.shortliner.payment.provider.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sweeps for payments stuck in PENDING — the case where this service charged
 * the provider but crashed (or was killed, or the pod was rescheduled)
 * before it could persist the result. Since nothing about a payment's
 * progress lives in JVM memory, any replica picking up the sweep can resolve
 * any stuck row; ownership of a particular payment was never pinned to the
 * instance that started it.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentService paymentService;
    private final PaymentMetrics metrics;
    private final Duration stuckAfter;

    public ReconciliationJob(PaymentRepository paymentRepository,
                              PaymentProvider paymentProvider,
                              PaymentService paymentService,
                              PaymentMetrics metrics,
                              @Value("${payment.reconciliation.stuck-after-ms:20000}") long stuckAfterMs) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.paymentService = paymentService;
        this.metrics = metrics;
        this.stuckAfter = Duration.ofMillis(stuckAfterMs);
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval-ms:30000}")
    public void reconcileStuckPayments() {
        Instant cutoff = Instant.now().minus(stuckAfter);
        List<Payment> stuck = paymentRepository.findByStatusAndCreatedAtBefore(PaymentStatus.PENDING, cutoff);

        if (stuck.isEmpty()) {
            return;
        }
        log.atInfo().addKeyValue("count", stuck.size()).log("Reconciliation found stuck PENDING payments");
        stuck.forEach(this::reconcileOne);
    }

    private void reconcileOne(Payment payment) {
        ChargeResult result = paymentProvider.checkStatus(payment.getIdempotencyKey());
        if (result == null) {
            log.atWarn()
                    .addKeyValue("paymentId", payment.getId())
                    .log("Provider has no record of this payment, leaving it PENDING for now");
            return;
        }

        paymentService.finalizePayment(payment.getId(), result);
        metrics.reconciliationResolved();
        log.atInfo()
                .addKeyValue("paymentId", payment.getId())
                .addKeyValue("status", result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED)
                .log("Reconciled stuck payment");
    }
}
