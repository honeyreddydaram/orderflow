package com.orderflow.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.product.TestJwtFactory;
import com.orderflow.product.config.JwtKeyConfig;
import com.orderflow.product.config.SecurityConfig;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import com.orderflow.product.exception.GlobalExceptionHandler;
import com.orderflow.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private static ProductResponse sampleProduct() {
        return new ProductResponse(UUID.randomUUID(), "Widget", "A widget",
                new BigDecimal("9.99"), 10, Instant.now(), Instant.now());
    }

    @Test
    void list_isPublic_noTokenRequired() throws Exception {
        when(productService.list(any())).thenReturn(new PageImpl<>(List.of(sampleProduct()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Widget"));
    }

    @Test
    void getById_isPublic_noTokenRequired() throws Exception {
        ProductResponse product = sampleProduct();
        when(productService.getById(product.id())).thenReturn(product);

        mockMvc.perform(get("/api/v1/products/{id}", product.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Widget"));
    }

    @Test
    void create_rejectsRequestWithoutToken() throws Exception {
        CreateProductRequest request = new CreateProductRequest("Widget", "desc", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(productService);
    }

    @Test
    void create_rejectsCustomerToken_withForbidden() throws Exception {
        CreateProductRequest request = new CreateProductRequest("Widget", "desc", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtFactory.customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(productService);
    }

    @Test
    void create_succeedsWithAdminToken() throws Exception {
        ProductResponse created = sampleProduct();
        when(productService.create(any())).thenReturn(created);
        CreateProductRequest request = new CreateProductRequest("Widget", "desc", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Widget"));
    }

    @Test
    void create_rejectsInvalidPayload_withBadRequest() throws Exception {
        CreateProductRequest invalid = new CreateProductRequest("", null, new BigDecimal("-1"), -5);

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_succeedsWithAdminToken() throws Exception {
        ProductResponse updated = sampleProduct();
        when(productService.update(eq(updated.id()), any())).thenReturn(updated);
        UpdateProductRequest request = new UpdateProductRequest("Widget Pro", "desc", new BigDecimal("14.99"), 5);

        mockMvc.perform(put("/api/v1/products/{id}", updated.id())
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    @Test
    void delete_succeedsWithAdminToken() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/products/{id}", id)
                        .header("Authorization", "Bearer " + TestJwtFactory.adminToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/v1/products/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(productService);
    }
}
