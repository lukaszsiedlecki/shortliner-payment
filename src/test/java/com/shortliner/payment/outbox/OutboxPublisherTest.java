package com.shortliner.payment.outbox;

import com.shortliner.payment.metrics.PaymentMetrics;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private PaymentMetrics metrics;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, metrics, 50, 100L);
    }

    @Test
    void marksEventPublishedOnlyAfterBrokerAck() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}");
        when(outboxEventRepository.lockNextBatch(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(metrics).outboxEventPublished();
        verify(metrics, never()).outboxPublishFailed();
    }

    @Test
    void failedSendLeavesEventPendingAndCountsFailure() {
        OutboxEvent failing = new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}");
        OutboxEvent ok = new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}");
        when(outboxEventRepository.lockNextBatch(50)).thenReturn(List.of(failing, ok));
        when(kafkaTemplate.send(anyString(), eq(failing.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(kafkaTemplate.send(anyString(), eq(ok.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(failing.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(ok.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(metrics).outboxPublishFailed();
        verify(metrics).outboxEventPublished();
    }

    @Test
    void unackedSendTimesOutAndLeavesEventPending() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}");
        when(outboxEventRepository.lockNextBatch(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<SendResult<String, String>>());

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(metrics).outboxPublishFailed();
    }

    @Test
    void synchronousSendExceptionIsTreatedAsFailure() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}");
        when(outboxEventRepository.lockNextBatch(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("metadata timeout"));

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verify(metrics).outboxPublishFailed();
    }
}
