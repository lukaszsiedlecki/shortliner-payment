package com.shortliner.payment.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentProviderTest {

    @Test
    void chargingTheSameKeyTwiceReturnsTheSameResult() {
        MockPaymentProvider provider = new MockPaymentProvider(0, 0, 0.0);

        ChargeResult first = provider.charge("key-1", BigDecimal.TEN, "USD");
        ChargeResult second = provider.charge("key-1", BigDecimal.TEN, "USD");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void checkStatusReturnsNullForAnUnknownKey() {
        MockPaymentProvider provider = new MockPaymentProvider(0, 0, 0.0);

        assertThat(provider.checkStatus("unknown")).isNull();
    }

    @Test
    void seedResultMakesCheckStatusReturnIt() {
        MockPaymentProvider provider = new MockPaymentProvider(0, 0, 0.0);
        ChargeResult seeded = ChargeResult.success("ref-x");

        provider.seedResult("key-2", seeded);

        assertThat(provider.checkStatus("key-2")).isEqualTo(seeded);
    }

    @Test
    void failureRateOneAlwaysFails() {
        MockPaymentProvider provider = new MockPaymentProvider(0, 0, 1.0);

        ChargeResult result = provider.charge("key-3", BigDecimal.TEN, "USD");

        assertThat(result.success()).isFalse();
    }
}
