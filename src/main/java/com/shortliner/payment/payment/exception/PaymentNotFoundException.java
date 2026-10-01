package com.shortliner.payment.payment.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    private final UUID paymentId;

    public PaymentNotFoundException(UUID id) {
        super("Payment not found: " + id);
        this.paymentId = id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}
