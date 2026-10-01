package com.shortliner.payment.payment.exception;

import com.shortliner.payment.payment.controller.PaymentController;
import com.shortliner.payment.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void unknownPathIs404WithoutErrorLog(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertThat(output).doesNotContain("Unexpected error");
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mockMvc.perform(delete("/api/payments"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void malformedBodyIs400WithoutEchoingIt() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": secret-garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }
}
