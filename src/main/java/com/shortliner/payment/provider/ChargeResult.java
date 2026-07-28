package com.shortliner.payment.provider;

/**
 * Outcome of a charge attempt (or a status lookup) against the payment
 * provider, keyed externally by idempotency key.
 */
public record ChargeResult(boolean success, String providerReference, String failureReason) {

    public static ChargeResult success(String providerReference) {
        return new ChargeResult(true, providerReference, null);
    }

    public static ChargeResult failure(String failureReason) {
        return new ChargeResult(false, null, failureReason);
    }
}
