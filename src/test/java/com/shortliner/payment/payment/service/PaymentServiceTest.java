package com.shortliner.payment.payment.service;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.exception.PaymentNotFoundException;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.provider.ChargeResult;
import com.shortliner.payment.provider.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentTransactionalOperations transactionalOperations;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentProvider paymentProvider;
    @Mock
    private PaymentMetrics metrics;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(transactionalOperations, paymentRepository, paymentProvider, metrics);
    }

    @Test
    void chargesProviderOnlyForANewPendingPayment() {
        Payment pending = new Payment("key-1", BigDecimal.TEN, "USD");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(pending, "id", id);

        Payment finalized = new Payment("key-1", BigDecimal.TEN, "USD");
        finalized.setStatus(PaymentStatus.SUCCESS);

        when(transactionalOperations.getOrCreatePending("key-1", BigDecimal.TEN, "USD")).thenReturn(pending);
        when(paymentProvider.charge("key-1", BigDecimal.TEN, "USD")).thenReturn(ChargeResult.success("ref-1"));
        when(transactionalOperations.finalizePayment(eq(id), any())).thenReturn(finalized);

        Payment result = paymentService.charge("key-1", BigDecimal.TEN, "USD");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(metrics).chargeAttempted();
        verify(paymentProvider).charge("key-1", BigDecimal.TEN, "USD");
        verify(transactionalOperations).finalizePayment(eq(id), any());
    }

    @Test
    void skipsProviderCallWhenPaymentWasAlreadyResolved() {
        Payment alreadyDone = new Payment("key-2", BigDecimal.TEN, "USD");
        alreadyDone.setStatus(PaymentStatus.SUCCESS);

        when(transactionalOperations.getOrCreatePending("key-2", BigDecimal.TEN, "USD")).thenReturn(alreadyDone);

        Payment result = paymentService.charge("key-2", BigDecimal.TEN, "USD");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verifyNoInteractions(paymentProvider);
        verify(transactionalOperations, never()).finalizePayment(any(), any());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getById(id)).isInstanceOf(PaymentNotFoundException.class);
    }
}
