package com.shortliner.payment.payment.controller;

import com.shortliner.payment.payment.dto.PaymentRequest;
import com.shortliner.payment.payment.dto.PaymentResponse;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Every endpoint here requires a valid Keycloak access token (SecurityConfig).
 * The caller's identity is always jwt.sub — never anything from the request
 * body, path or headers.
 */
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
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Client-generated key identifying this charge attempt, unique per user", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        Payment payment = paymentService.charge(jwt.getSubject(), idempotencyKey, request.amount(), request.currency());
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    @GetMapping
    @Operation(summary = "List the caller's payments, newest first")
    public ResponseEntity<PagedModel<PaymentResponse>> listMyPayments(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(new PagedModel<>(
                paymentService.listForUser(jwt.getSubject(), page, size).map(PaymentResponse::from)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by id",
            description = "Only the owner (or an admin) can read a payment; anyone else gets 404.")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id,
                                                      @AuthenticationPrincipal Jwt jwt,
                                                      Authentication authentication) {
        Payment payment = paymentService.getForCaller(id, jwt.getSubject(), isAdmin(authentication));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_admin".equals(authority.getAuthority()));
    }
}
