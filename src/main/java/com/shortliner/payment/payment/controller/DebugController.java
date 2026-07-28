package com.shortliner.payment.payment.controller;

import com.shortliner.payment.payment.dto.PaymentRequest;
import com.shortliner.payment.payment.dto.PaymentResponse;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.provider.ChargeResult;
import com.shortliner.payment.provider.MockPaymentProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Not part of the payment API — a hands-on way to reproduce the exact
 * failure the reconciliation job exists for. It creates a PENDING payment
 * and seeds the mock provider as if the charge had already succeeded there,
 * without ever calling finalizePayment — simulating this service crashing
 * between charging and persisting. Call this, GET the payment (PENDING),
 * wait past payment.reconciliation.stuck-after-ms, GET it again (SUCCESS).
 * Disabled in production via payment.debug.enabled=false.
 */
@RestController
@RequestMapping("/api/payments/_debug")
@ConditionalOnProperty(name = "payment.debug.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Debug", description = "Local-only endpoints for exercising failure modes")
public class DebugController {

    private final PaymentRepository paymentRepository;
    private final MockPaymentProvider mockPaymentProvider;

    public DebugController(PaymentRepository paymentRepository, MockPaymentProvider mockPaymentProvider) {
        this.paymentRepository = paymentRepository;
        this.mockPaymentProvider = mockPaymentProvider;
    }

    @PostMapping("/simulate-stuck-payment")
    @Operation(summary = "Create a payment stuck in PENDING with a provider-side result already recorded",
            description = "Simulates a crash between charging the provider and persisting the result, "
                    + "for exercising the reconciliation job by hand.")
    public ResponseEntity<PaymentResponse> simulateStuckPayment(@Valid @RequestBody PaymentRequest request) {
        String idempotencyKey = "debug-" + UUID.randomUUID();
        Payment payment = paymentRepository.save(new Payment(idempotencyKey, request.amount(), request.currency()));
        mockPaymentProvider.seedResult(idempotencyKey, ChargeResult.success("debug-ref-" + payment.getId()));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }
}
