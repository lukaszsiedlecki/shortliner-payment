package com.shortliner.payment.payment.controller;

import com.shortliner.payment.payment.dto.PaymentRequest;
import com.shortliner.payment.payment.dto.PaymentResponse;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@Validated
@Tag(name = "Payments", description = "Premium plan payment endpoints")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Charge a payment",
            description = "Safe to retry with the same Idempotency-Key: retries of an in-flight or completed "
                    + "charge return the existing payment instead of charging again.")
    public ResponseEntity<PaymentResponse> createPayment(
            @Parameter(description = "Client-generated key identifying this charge attempt", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        Payment payment = paymentService.charge(idempotencyKey, request.amount(), request.currency());
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by id")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentResponse.from(paymentService.getById(id)));
    }
}
