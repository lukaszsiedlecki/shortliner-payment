package com.shortliner.payment.payment.exception;

import com.shortliner.payment.payment.PaymentStatus;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Cannot transition payment from " + from + " to " + to);
    }
}
