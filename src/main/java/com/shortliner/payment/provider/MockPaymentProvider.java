package com.shortliner.payment.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fake gateway. Its ledger keyed by idempotency key exists only to mimic what
 * a real gateway does internally (return the same result if you charge the
 * same key twice) so {@link #checkStatus} has something to answer with — it
 * is NOT the idempotency safeguard for this service. That safeguard is the
 * unique constraint on payments.idempotency_key; this map would just vanish
 * on restart or diverge across replicas if we ever relied on it for that.
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);

    private final Map<String, ChargeResult> ledger = new ConcurrentHashMap<>();
    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final double failureRate;

    public MockPaymentProvider(
            @Value("${payment.provider.mock.min-latency-ms:50}") long minLatencyMs,
            @Value("${payment.provider.mock.max-latency-ms:200}") long maxLatencyMs,
            @Value("${payment.provider.mock.failure-rate:0.1}") double failureRate) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.failureRate = failureRate;
    }

    @Override
    public ChargeResult charge(String idempotencyKey, BigDecimal amount, String currency) {
        ChargeResult cached = ledger.get(idempotencyKey);
        if (cached != null) {
            log.debug("Provider already has a result for this idempotency key, replaying it");
            return cached;
        }

        simulateLatency();

        ChargeResult result = ThreadLocalRandom.current().nextDouble() < failureRate
                ? ChargeResult.failure("mock provider declined the charge")
                : ChargeResult.success("mock-" + UUID.randomUUID());

        // putIfAbsent: two concurrent callers with the same key (possible
        // since this mock is a singleton shared across the app, unlike a
        // real gateway) must not overwrite each other's result.
        ChargeResult raced = ledger.putIfAbsent(idempotencyKey, result);
        return raced != null ? raced : result;
    }

    @Override
    public ChargeResult checkStatus(String idempotencyKey) {
        return ledger.get(idempotencyKey);
    }

    /**
     * Test/demo seam: seeds a provider-side result without going through
     * {@link #charge}, simulating a charge the provider processed but that
     * this service crashed before it could persist — exactly the gap the
     * reconciliation job exists to close.
     */
    public void seedResult(String idempotencyKey, ChargeResult result) {
        ledger.put(idempotencyKey, result);
    }

    private void simulateLatency() {
        if (maxLatencyMs <= 0) {
            return;
        }
        long delay = minLatencyMs + (long) (ThreadLocalRandom.current().nextDouble() * (maxLatencyMs - minLatencyMs));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
