package com.shortliner.payment.reconciliation;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.payment.service.PaymentService;
import com.shortliner.payment.provider.ChargeResult;
import com.shortliner.payment.provider.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationJobTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentProvider paymentProvider;
    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentMetrics metrics;

    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new ReconciliationJob(paymentRepository, paymentProvider, paymentService, metrics, 20_000L);
    }

    @Test
    void resolvesStuckPaymentsUsingProviderStatus() {
        Payment stuck = new Payment("user-1", "key-1", BigDecimal.TEN, "USD");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(stuck, "id", id);

        when(paymentRepository.findByStatusAndCreatedAtBefore(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of(stuck));
        // Looked up by payment ID — the key the provider was charged with.
        when(paymentProvider.checkStatus(id.toString())).thenReturn(ChargeResult.success("ref-1"));

        job.reconcileStuckPayments();

        verify(paymentService).finalizePayment(eq(id), any());
        verify(metrics).reconciliationResolved();
    }

    @Test
    void leavesPaymentAloneWhenProviderHasNoRecord() {
        Payment stuck = new Payment("user-1", "key-2", BigDecimal.TEN, "USD");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(stuck, "id", id);

        when(paymentRepository.findByStatusAndCreatedAtBefore(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of(stuck));
        when(paymentProvider.checkStatus(id.toString())).thenReturn(null);

        job.reconcileStuckPayments();

        verifyNoInteractions(paymentService);
        verifyNoInteractions(metrics);
    }
}
