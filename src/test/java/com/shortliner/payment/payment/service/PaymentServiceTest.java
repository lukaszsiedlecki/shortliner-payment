package com.shortliner.payment.payment.service;

import com.shortliner.payment.metrics.ChargeOutcome;
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
        Payment pending = paymentWithId("user-1", "key-1");
        UUID id = pending.getId();

        Payment finalized = new Payment("user-1", "key-1", BigDecimal.TEN, "USD");
        finalized.setStatus(PaymentStatus.SUCCESS);

        when(transactionalOperations.getOrCreatePending("user-1", "key-1", BigDecimal.TEN, "USD")).thenReturn(pending);
        when(paymentProvider.charge(id.toString(), BigDecimal.TEN, "USD")).thenReturn(ChargeResult.success("ref-1"));
        when(transactionalOperations.finalizePayment(eq(id), any())).thenReturn(finalized);

        Payment result = paymentService.charge("user-1", "key-1", BigDecimal.TEN, "USD");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(metrics).chargeAttempted();
        verify(metrics).chargeCompleted(eq(ChargeOutcome.SUCCESS), any());
        // The provider is keyed by payment ID, never by the client's
        // per-user idempotency key.
        verify(paymentProvider).charge(id.toString(), BigDecimal.TEN, "USD");
        verify(transactionalOperations).finalizePayment(eq(id), any());
    }

    @Test
    void recordsProviderErrorOutcomeAndRethrows() {
        Payment pending = paymentWithId("user-1", "key-3");

        when(transactionalOperations.getOrCreatePending("user-1", "key-3", BigDecimal.TEN, "USD")).thenReturn(pending);
        when(paymentProvider.charge(pending.getId().toString(), BigDecimal.TEN, "USD"))
                .thenThrow(new IllegalStateException("gateway down"));

        assertThatThrownBy(() -> paymentService.charge("user-1", "key-3", BigDecimal.TEN, "USD"))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics).chargeCompleted(eq(ChargeOutcome.ERROR), any());
        verify(transactionalOperations, never()).finalizePayment(any(), any());
    }

    @Test
    void skipsProviderCallWhenPaymentWasAlreadyResolved() {
        Payment alreadyDone = new Payment("user-1", "key-2", BigDecimal.TEN, "USD");
        alreadyDone.setStatus(PaymentStatus.SUCCESS);

        when(transactionalOperations.getOrCreatePending("user-1", "key-2", BigDecimal.TEN, "USD")).thenReturn(alreadyDone);

        Payment result = paymentService.charge("user-1", "key-2", BigDecimal.TEN, "USD");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verifyNoInteractions(paymentProvider);
        verify(transactionalOperations, never()).finalizePayment(any(), any());
    }

    @Test
    void ownerCanReadTheirPayment() {
        Payment payment = paymentWithId("user-1", "key-1");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThat(paymentService.getForCaller(payment.getId(), "user-1", false)).isSameAs(payment);
    }

    @Test
    void otherUsersPaymentIsReportedAsNotFound() {
        Payment payment = paymentWithId("user-1", "key-1");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.getForCaller(payment.getId(), "user-2", false))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void adminCanReadAnyPayment() {
        Payment payment = paymentWithId("user-1", "key-1");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThat(paymentService.getForCaller(payment.getId(), "admin-1", true)).isSameAs(payment);
    }

    @Test
    void ownerlessHistoricalPaymentIsOnlyVisibleToAdmins() {
        Payment legacy = paymentWithId("user-1", "key-1");
        ReflectionTestUtils.setField(legacy, "userId", null);
        when(paymentRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> paymentService.getForCaller(legacy.getId(), "user-1", false))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThat(paymentService.getForCaller(legacy.getId(), "admin-1", true)).isSameAs(legacy);
    }

    @Test
    void getForCallerThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getForCaller(id, "user-1", true))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    private static Payment paymentWithId(String userId, String idempotencyKey) {
        Payment payment = new Payment(userId, idempotencyKey, BigDecimal.TEN, "USD");
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }
}
