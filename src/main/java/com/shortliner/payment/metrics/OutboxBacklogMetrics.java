package com.shortliner.payment.metrics;

import com.shortliner.payment.outbox.OutboxStatus;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Is the outbox stuck?" gauges. Values come from a cached DB snapshot
 * refreshed on its own schedule rather than from a query per scrape — every
 * replica gets scraped, and the scrape thread shouldn't depend on the DB.
 * <p>
 * Refreshed by its own scheduled method rather than from OutboxPublisher:
 * the publisher is exactly what blocks when Kafka is unreachable, and these
 * gauges must keep moving precisely then. The age gauge is computed at
 * scrape time from the cached oldest {@code created_at}, so it keeps
 * climbing between refreshes (and even if refreshing itself stalls).
 * <p>
 * Both values are global (same table for every replica), so in PromQL
 * aggregate them with {@code max}, not {@code sum}.
 */
@Component
public class OutboxBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicReference<Instant> oldestPendingCreatedAt = new AtomicReference<>();

    @Autowired
    public OutboxBacklogMetrics(OutboxEventRepository outboxEventRepository, MeterRegistry registry) {
        this(outboxEventRepository, registry, Clock.systemUTC());
    }

    OutboxBacklogMetrics(OutboxEventRepository outboxEventRepository, MeterRegistry registry, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
        Gauge.builder("shortliner.payment.outbox.pending", pendingCount, AtomicLong::get)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
        Gauge.builder("shortliner.payment.outbox.oldest.pending.age", this, OutboxBacklogMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished outbox event; 0 when the outbox is empty")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.metrics-refresh-ms:15000}")
    public void refresh() {
        try {
            pendingCount.set(outboxEventRepository.countByStatus(OutboxStatus.PENDING));
            oldestPendingCreatedAt.set(outboxEventRepository.findOldestCreatedAtByStatus(OutboxStatus.PENDING));
        } catch (RuntimeException e) {
            // Keep the last known values: the age gauge keeps growing on its
            // own, which is the right signal if the DB is what's broken.
            log.warn("Failed to refresh outbox backlog metrics", e);
        }
    }

    double oldestPendingAgeSeconds() {
        Instant oldest = oldestPendingCreatedAt.get();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }
}
