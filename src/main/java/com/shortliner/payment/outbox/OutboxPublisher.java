package com.shortliner.payment.outbox;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox table and ships PENDING rows to Kafka. Safe to run on
 * every replica concurrently: {@link OutboxEventRepository#lockNextBatch}
 * uses FOR UPDATE SKIP LOCKED, so each instance only ever grabs rows no other
 * instance currently holds — no coordination between replicas needed.
 * <p>
 * The Kafka send happens inside the same transaction as marking the row
 * PUBLISHED, kept synchronous for simplicity: the batch is sent, then every
 * send's broker ack is awaited before its row is marked. Only acked rows
 * flip to PUBLISHED; a failed or timed-out send leaves its row PENDING to be
 * retried on the next poll (at-least-once). Marking rows without awaiting
 * the ack would let the commit race ahead of a send that later fails —
 * silently losing the event. A production system at scale
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
    private final long sendTimeoutMs;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            PaymentMetrics metrics,
                            @Value("${payment.outbox.batch-size:50}") int batchSize,
                            @Value("${payment.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox event(s)", batch.size());
        // Send the whole batch first so the producer can pipeline it, then
        // wait for each ack against one shared deadline, so a dead broker
        // costs one timeout per batch rather than one per event.
        List<CompletableFuture<SendResult<String, String>>> sends = batch.stream()
                .map(this::send)
                .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);

        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            if (awaitAck(event, sends.get(i), deadline)) {
                event.markPublished();
                metrics.outboxEventPublished();
            } else {
                metrics.outboxPublishFailed();
            }
        }
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxEvent event) {
        try {
            return kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload());
        } catch (RuntimeException e) {
            // send() itself throws when e.g. broker metadata can't be fetched
            // within max.block.ms — treat it the same as an async failure.
            return CompletableFuture.failedFuture(e);
        }
    }

    private boolean awaitAck(OutboxEvent event, CompletableFuture<SendResult<String, String>> send, long deadline) {
        try {
            send.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.atWarn()
                    .addKeyValue("outboxEventId", event.getId())
                    .addKeyValue("paymentId", event.getAggregateId())
                    .setCause(cause)
                    .log("Kafka send failed, outbox event stays PENDING for retry");
            return false;
        }
    }
}
