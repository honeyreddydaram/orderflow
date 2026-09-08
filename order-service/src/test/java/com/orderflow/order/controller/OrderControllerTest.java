package com.orderflow.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.order.TestJwtFactory;
import com.orderflow.order.config.JwtKeyConfig;
import com.orderflow.order.config.SecurityConfig;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.exception.GlobalExceptionHandler;
import com.orderflow.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private static OrderResponse sampleOrder(UUID userId) {
        return new OrderResponse(UUID.randomUUID(), userId, com.orderflow.order.domain.OrderStatus.PENDING,
                new BigDecimal("19.98"), List.of(), Instant.now(), Instant.now());
    }

    @Test
    void create_rejectsRequestWithoutToken() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_succeedsWithValidToken_returns201WhenNewOrder() throws Exception {
        UUID userId = UUID.randomUUID();
        OrderResponse created = sampleOrder(userId);
        when(orderService.create(eq(userId), any(), isNull(), any()))
                .thenReturn(new OrderService.CreateResult(created, false));

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void create_returns200_whenIdempotentReplay() throws Exception {
        UUID userId = UUID.randomUUID();
        OrderResponse existing = sampleOrder(userId);
        when(orderService.create(eq(userId), any(), eq("replay-key"), any()))
                .thenReturn(new OrderService.CreateResult(existing, true));

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId))
                        .header("Idempotency-Key", "replay-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    @Test
    void create_rejectsEmptyItems_withBadRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(List.of());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_returnsOrder_forOwner() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(orderService.getById(eq(orderId), eq(userId), eq(false))).thenReturn(sampleOrder(userId));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returnsForbidden_whenServiceDeniesAccess() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(orderService.getById(eq(orderId), eq(userId), eq(false)))
                .thenThrow(new AccessDeniedException("not yours"));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_returnsNotFound_whenOrderMissing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(orderService.getById(eq(orderId), eq(userId), eq(false)))
                .thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsPage_forAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        when(orderService.listForUser(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(sampleOrder(userId)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(userId.toString()));
    }
}
