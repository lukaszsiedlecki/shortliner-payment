package com.shortliner.payment.metrics;

import com.shortliner.payment.provider.ChargeResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMetricsTest {

    @Test
    void chargeTimerIsTaggedByOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMetrics metrics = new PaymentMetrics(registry);

        metrics.chargeCompleted(ChargeOutcome.SUCCESS, Duration.ofMillis(120));
        metrics.chargeCompleted(ChargeOutcome.DECLINED, Duration.ofMillis(80));
        metrics.chargeCompleted(ChargeOutcome.DECLINED, Duration.ofMillis(90));

        assertThat(registry.get("shortliner.payment.charge").tag("result", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("shortliner.payment.charge").tag("result", "declined").timer().count()).isEqualTo(2);
        assertThat(registry.get("shortliner.payment.charge").tag("result", "timeout").timer().count()).isZero();
    }

    @Test
    void outboxFailuresHaveTheirOwnCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMetrics metrics = new PaymentMetrics(registry);

        metrics.outboxEventPublished();
        metrics.outboxPublishFailed();
        metrics.outboxPublishFailed();

        assertThat(registry.get("outbox.events.published").counter().count()).isEqualTo(1);
        assertThat(registry.get("shortliner.payment.outbox.publish.failures").counter().count()).isEqualTo(2);
    }

    @Test
    void classifiesChargeOutcomes() {
        assertThat(ChargeOutcome.of(ChargeResult.success("ref"))).isEqualTo(ChargeOutcome.SUCCESS);
        assertThat(ChargeOutcome.of(ChargeResult.failure("nope"))).isEqualTo(ChargeOutcome.DECLINED);
        assertThat(ChargeOutcome.of(new IllegalStateException("boom"))).isEqualTo(ChargeOutcome.ERROR);
        assertThat(ChargeOutcome.of(new RuntimeException(new SocketTimeoutException()))).isEqualTo(ChargeOutcome.TIMEOUT);
        assertThat(ChargeOutcome.of(new RuntimeException(new TimeoutException()))).isEqualTo(ChargeOutcome.TIMEOUT);
    }
}
