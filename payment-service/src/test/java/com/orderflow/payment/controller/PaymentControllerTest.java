package com.orderflow.payment.controller;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.payment.TestJwtFactory;
import com.orderflow.payment.config.JwtKeyConfig;
import com.orderflow.payment.config.SecurityConfig;
import com.orderflow.payment.domain.PaymentStatus;
import com.orderflow.payment.dto.PaymentResponse;
import com.orderflow.payment.exception.GlobalExceptionHandler;
import com.orderflow.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    private static PaymentResponse sampleResponse(UUID orderId, UUID userId) {
        return new PaymentResponse(UUID.randomUUID(), orderId, userId, new BigDecimal("29.97"),
                PaymentStatus.COMPLETED, "TXN-1", null, Instant.now(), Instant.now());
    }

    @Test
    void getByOrderId_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{orderId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    @Test
    void getByOrderId_returnsForbidden_whenNotOwnerOrAdmin() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        when(paymentService.getPayment(eq(orderId), eq(requesterId), eq(false)))
                .thenThrow(new AccessDeniedException("not yours"));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(requesterId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getByOrderId_succeeds_forOwner() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(paymentService.getPayment(orderId, userId, false)).thenReturn(sampleResponse(orderId, userId));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void getByOrderId_succeeds_forAdmin() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(paymentService.getPayment(orderId, adminId, true)).thenReturn(sampleResponse(orderId, ownerId));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    @Test
    void getByOrderId_returnsNotFound_whenMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(paymentService.getPayment(eq(orderId), eq(userId), any(Boolean.class)))
                .thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/v1/payments/{orderId}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isNotFound());
    }
}
