package com.shortliner.payment.payment.controller;

import com.shortliner.payment.config.SecurityConfig;
import com.shortliner.payment.payment.entity.Payment;
import com.shortliner.payment.payment.repository.PaymentRepository;
import com.shortliner.payment.provider.MockPaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DebugController.class, properties = "payment.debug.enabled=true")
@Import(SecurityConfig.class)
class DebugControllerSecurityTest {

    private static final String BODY = "{\"amount\": 10.00, \"currency\": \"USD\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRepository paymentRepository;
    @MockitoBean
    private MockPaymentProvider mockPaymentProvider;

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(post("/api/payments/_debug/simulate-stuck-payment")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserIs403() throws Exception {
        mockMvc.perform(post("/api/payments/_debug/simulate-stuck-payment")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentRepository, mockPaymentProvider);
    }

    @Test
    void adminCanSimulateAndSeedsProviderUnderThePaymentId() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", id);
            return p;
        });

        mockMvc.perform(post("/api/payments/_debug/simulate-stuck-payment")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(paymentRepository).save(argThat(p -> "admin-1".equals(p.getUserId())));
        verify(mockPaymentProvider).seedResult(eq(id.toString()), any());
    }
}
