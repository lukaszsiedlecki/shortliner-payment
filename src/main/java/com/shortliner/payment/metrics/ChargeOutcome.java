package com.shortliner.payment.metrics;

import com.shortliner.payment.provider.ChargeResult;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Bounded set of values for the {@code result} tag on the provider charge
 * timer. Deliberately coarse: provider decline reasons and exception
 * messages are free text and would blow up metric cardinality.
 */
public enum ChargeOutcome {
    SUCCESS,
    DECLINED,
    ERROR,
    TIMEOUT;

    public static ChargeOutcome of(ChargeResult result) {
        return result.success() ? SUCCESS : DECLINED;
    }

    public static ChargeOutcome of(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return TIMEOUT;
            }
        }
        return ERROR;
    }

    String tagValue() {
        return name().toLowerCase();
    }
}
