package com.orderflow.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.TestJwtFactory;
import com.orderflow.inventory.config.JwtKeyConfig;
import com.orderflow.inventory.config.SecurityConfig;
import com.orderflow.inventory.dto.AdjustStockRequest;
import com.orderflow.inventory.dto.CreateStockRequest;
import com.orderflow.inventory.dto.StockResponse;
import com.orderflow.inventory.exception.GlobalExceptionHandler;
import com.orderflow.inventory.exception.StockAlreadyExistsException;
import com.orderflow.inventory.service.InventoryService;
import com.orderflow.common.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryService inventoryService;

    private static StockResponse sampleStock(UUID productId) {
        return new StockResponse(productId, 10, 0, Instant.now(), Instant.now());
    }

    @Test
    void getStock_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getStock_rejectsCustomerToken_withForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inventoryService);
    }

    @Test
    void getStock_succeedsWithAdminToken() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.getStock(productId)).thenReturn(sampleStock(productId));

        mockMvc.perform(get("/api/v1/inventory/{id}", productId)
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()));
    }

    @Test
    void getStock_returnsNotFound_whenMissing() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.getStock(productId)).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/v1/inventory/{id}", productId)
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createStock_succeedsWithAdminToken() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.createStock(any())).thenReturn(sampleStock(productId));

        mockMvc.perform(post("/api/v1/inventory")
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new CreateStockRequest(productId, 10))))
                .andExpect(status().isCreated());
    }

    @Test
    void createStock_returnsConflict_whenAlreadyExists() throws Exception {
        when(inventoryService.createStock(any())).thenThrow(new StockAlreadyExistsException("already exists"));

        mockMvc.perform(post("/api/v1/inventory")
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new CreateStockRequest(UUID.randomUUID(), 10))))
                .andExpect(status().isConflict());
    }

    @Test
    void createStock_rejectsInvalidPayload_withBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/inventory")
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new CreateStockRequest(null, -5))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adjustStock_succeedsWithAdminToken() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.adjustStock(eq(productId), eq(5))).thenReturn(sampleStock(productId));

        mockMvc.perform(put("/api/v1/inventory/{id}/adjust", productId)
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AdjustStockRequest(5))))
                .andExpect(status().isOk());
    }
}
