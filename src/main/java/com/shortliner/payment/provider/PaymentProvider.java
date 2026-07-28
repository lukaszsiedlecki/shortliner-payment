package com.shortliner.payment.provider;

import java.math.BigDecimal;

/**
 * Stand-in for a real payment gateway (Stripe, Adyen, ...). Every real
 * gateway worth using accepts a client-provided idempotency key so retries of
 * the exact same charge don't double-bill — this interface mirrors that.
 */
public interface PaymentProvider {

    ChargeResult charge(String idempotencyKey, BigDecimal amount, String currency);

    /**
     * Looks up what the provider actually did for a given idempotency key.
     * Used by the reconciliation job to resolve a payment that got stuck
     * PENDING — e.g. this service crashed after the provider processed the
     * charge but before the result was persisted. Returns null if the
     * provider has no record of that key at all.
     */
    ChargeResult checkStatus(String idempotencyKey);
}
