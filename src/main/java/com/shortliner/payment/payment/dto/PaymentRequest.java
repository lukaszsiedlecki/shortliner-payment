package com.shortliner.payment.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency
) {
}
