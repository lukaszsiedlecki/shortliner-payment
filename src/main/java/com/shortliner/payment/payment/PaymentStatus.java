package com.shortliner.payment.payment;

/**
 * Explicit payment state machine: PENDING is the only starting state, and
 * SUCCESS/FAILED are terminal — once reached, no further transition is legal.
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED;

    public boolean canTransitionTo(PaymentStatus target) {
        return this == PENDING && (target == SUCCESS || target == FAILED);
    }
}
