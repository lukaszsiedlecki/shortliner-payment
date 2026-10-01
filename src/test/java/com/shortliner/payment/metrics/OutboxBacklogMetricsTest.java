package com.shortliner.payment.metrics;

import com.shortliner.payment.outbox.OutboxStatus;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxBacklogMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private SimpleMeterRegistry registry;
    private OutboxBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OutboxBacklogMetrics(outboxEventRepository, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportsPendingCountAndOldestAgeAfterRefresh() {
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(7L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING)).thenReturn(NOW.minusSeconds(90));

        metrics.refresh();

        assertThat(registry.get("shortliner.payment.outbox.pending").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("shortliner.payment.outbox.oldest.pending.age").gauge().value()).isEqualTo(90.0);
    }

    @Test
    void ageIsZeroWhenOutboxIsEmpty() {
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING)).thenReturn(null);

        metrics.refresh();

        assertThat(registry.get("shortliner.payment.outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("shortliner.payment.outbox.oldest.pending.age").gauge().value()).isZero();
    }

    @Test
    void keepsLastKnownValuesWhenRefreshFails() {
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(3L);
        when(outboxEventRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING)).thenReturn(NOW.minusSeconds(10));
        metrics.refresh();

        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenThrow(new RuntimeException("db down"));
        metrics.refresh();

        assertThat(registry.get("shortliner.payment.outbox.pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("shortliner.payment.outbox.oldest.pending.age").gauge().value()).isEqualTo(10.0);
    }
}
