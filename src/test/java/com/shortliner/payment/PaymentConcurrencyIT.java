package com.shortliner.payment;

import com.shortliner.payment.outbox.OutboxPublisher;
import com.shortliner.payment.outbox.OutboxStatus;
import com.shortliner.payment.outbox.entity.OutboxEvent;
import com.shortliner.payment.outbox.repository.OutboxEventRepository;
import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.dto.PaymentRequest;
import com.shortliner.payment.payment.dto.PaymentResponse;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs against a real Postgres (via Testcontainers) rather than H2 because
 * both behaviors under test are Postgres-specific and easy to get subtly
 * wrong in a way H2 wouldn't catch: a unique-constraint violation aborting
 * the current transaction, and FOR UPDATE SKIP LOCKED.
 * <p>
 * Kafka delivery isn't what either test is about, so KafkaTemplate is
 * mocked rather than pulling in a second Testcontainers module.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentConcurrencyIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyProduceExactlyOnePayment() throws InterruptedException {
        String idempotencyKey = "concurrency-test-" + UUID.randomUUID();
        int concurrentRequests = 20;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulHttpCalls = new AtomicInteger();

        List<Runnable> tasks = IntStream.range(0, concurrentRequests)
                .<Runnable>mapToObj(i -> () -> {
                    awaitUninterruptibly(start);
                    ResponseEntity<PaymentResponse> response = postPayment(idempotencyKey);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        successfulHttpCalls.incrementAndGet();
                    }
                })
                .toList();

        tasks.forEach(executor::execute);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Every request got a clean response: none errored out fighting
        // over the idempotency key.
        assertThat(successfulHttpCalls.get()).isEqualTo(concurrentRequests);

        // But the DB unique constraint let exactly one payment row exist,
        // no matter how many replicas/threads raced to create it.
        List<Payment> payments = paymentRepository.findAll();
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void skipLockedPreventsTwoConcurrentPublishersFromSendingTheSameEvent() throws InterruptedException {
        int eventCount = 10;
        for (int i = 0; i < eventCount; i++) {
            outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "PaymentCompleted", "{}"));
        }
        // The publisher awaits each send's broker ack before marking a row.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Simulates two replicas' schedulers firing at the same time.
        CountDownLatch start = new CountDownLatch(1);
        Runnable publish = () -> {
            awaitUninterruptibly(start);
            outboxPublisher.publishPending();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.execute(publish);
        executor.execute(publish);
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED);
        // Exactly one send per event in total across both publisher runs —
        // if SKIP LOCKED weren't doing its job, some event would have been
        // grabbed and sent by both concurrent calls.
        verify(kafkaTemplate, times(eventCount)).send(anyString(), anyString(), anyString());
    }

    private ResponseEntity<PaymentResponse> postPayment(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<PaymentRequest> entity = new HttpEntity<>(new PaymentRequest(BigDecimal.TEN, "USD"), headers);
        return restTemplate.postForEntity("/api/payments", entity, PaymentResponse.class);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
