package com.shortliner.payment.payment.repository;

import com.shortliner.payment.payment.PaymentStatus;
import com.shortliner.payment.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, Instant cutoff);
}
