package com.shortliner.payment.outbox;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls the outbox table and ships PENDING rows to Kafka. Safe to run on
 * every replica concurrently: {@link OutboxEventRepository#lockNextBatch}
 * uses FOR UPDATE SKIP LOCKED, so each instance only ever grabs rows no other
 * instance currently holds — no coordination between replicas needed.
 * <p>
 * The Kafka send happens inside the same transaction as marking the row
 * PUBLISHED, kept synchronous for simplicity. A production system at scale
 * would likely decouple the two (async send + separate ack step) to avoid
 * holding row locks across a network call, but for this project's scale
 * that tradeoff isn't worth the extra moving parts.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TOPIC = "shortliner.payments.completed";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentMetrics metrics;
    private final int batchSize;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            PaymentMetrics metrics,
                            @Value("${payment.outbox.batch-size:50}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox event(s)", batch.size());
        for (OutboxEvent event : batch) {
            kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload());
            event.markPublished();
            metrics.outboxEventPublished();
        }
    }
}
