package com.shortliner.payment.payment.controller;

import com.shortliner.payment.config.SecurityConfig;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.exception.PaymentNotFoundException;
import com.shortliner.payment.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access rules for the payment API. Uses the real SecurityConfig, with
 * spring-security-test standing in for Keycloak-issued tokens.
 */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerSecurityTest {

    private static final String BODY = "{\"amount\": 10.00, \"currency\": \"USD\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void anonymousCallerGets401Everywhere() throws Exception {
        mockMvc.perform(post("/api/payments").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    @Test
    void chargeUsesTheTokenSubjectAsOwner() throws Exception {
        when(paymentService.charge(anyString(), anyString(), any(), anyString()))
                .thenReturn(payment("user-1"));

        mockMvc.perform(post("/api/payments")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(paymentService).charge("user-1", "k-1", new BigDecimal("10.00"), "USD");
    }

    @Test
    void ownerReadsTheirPaymentAsNonAdmin() throws Exception {
        Payment payment = payment("user-1");
        when(paymentService.getForCaller(payment.getId(), "user-1", false)).thenReturn(payment);

        mockMvc.perform(get("/api/payments/{id}", payment.getId())
                        .with(jwt().jwt(j -> j.subject("user-1")).authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()));
    }

    @Test
    void otherUsersPaymentIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.getForCaller(id, "user-2", false)).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/api/payments/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-2")).authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminRoleIsPassedThroughForReads() throws Exception {
        Payment payment = payment("user-1");
        when(paymentService.getForCaller(payment.getId(), "admin-1", true)).thenReturn(payment);

        mockMvc.perform(get("/api/payments/{id}", payment.getId())
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(
                                new SimpleGrantedAuthority("ROLE_user"), new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk());
    }

    @Test
    void listReturnsOnlyTheCallersPayments() throws Exception {
        when(paymentService.listForUser("user-1", 0, 20))
                .thenReturn(new PageImpl<>(List.of(payment("user-1")), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/payments").with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        verify(paymentService).listForUser(eq("user-1"), anyInt(), anyInt());
    }

    @Test
    void invalidTokenIs401() throws Exception {
        // No jwt() post-processor: the real decoder sees a garbage token and
        // rejects it before anything tries to fetch the JWKS.
        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(paymentService);
    }

    private static Payment payment(String userId) {
        Payment payment = new Payment(userId, "k-" + UUID.randomUUID(), new BigDecimal("10.00"), "USD");
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }
}
