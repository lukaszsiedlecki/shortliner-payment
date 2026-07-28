package com.shortliner.payment.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void pendingCanTransitionToSuccessOrFailed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.SUCCESS)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
    }

    @Test
    void terminalStatesCannotTransitionAnywhere() {
        assertThat(PaymentStatus.SUCCESS.canTransitionTo(PaymentStatus.FAILED)).isFalse();
        assertThat(PaymentStatus.SUCCESS.canTransitionTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.SUCCESS)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    void pendingCannotTransitionToItself() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }
}
