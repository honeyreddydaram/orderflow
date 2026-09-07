package com.orderflow.product.controller;

import com.orderflow.product.TestJwtFactory;
import com.orderflow.product.dto.CreateProductRequest;
import com.orderflow.product.dto.ProductResponse;
import com.orderflow.product.dto.UpdateProductRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/products";
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtFactory.adminToken());
        return headers;
    }

    @Test
    void fullProductLifecycle_createReadUpdateListDeleteWithCacheInvalidation() {
        CreateProductRequest createRequest = new CreateProductRequest("Widget", "A widget", new BigDecimal("9.99"), 10);
        ResponseEntity<ProductResponse> createResponse = restTemplate.postForEntity(
                baseUrl(), new HttpEntity<>(createRequest, adminHeaders()), ProductResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse created = createResponse.getBody();
        assertThat(created.name()).isEqualTo("Widget");

        // Populate the single-product cache.
        ResponseEntity<ProductResponse> firstRead = restTemplate.getForEntity(baseUrl() + "/" + created.id(), ProductResponse.class);
        assertThat(firstRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRead.getBody().price()).isEqualByComparingTo("9.99");

        // Update must evict the cached entry - a stale read here would prove eviction failed.
        UpdateProductRequest updateRequest = new UpdateProductRequest("Widget Pro", "Better widget", new BigDecimal("14.99"), 20);
        ResponseEntity<ProductResponse> updateResponse = restTemplate.exchange(
                baseUrl() + "/" + created.id(), HttpMethod.PUT,
                new HttpEntity<>(updateRequest, adminHeaders()), ProductResponse.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ProductResponse> readAfterUpdate = restTemplate.getForEntity(baseUrl() + "/" + created.id(), ProductResponse.class);
        assertThat(readAfterUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readAfterUpdate.getBody().name()).isEqualTo("Widget Pro");
        assertThat(readAfterUpdate.getBody().price()).isEqualByComparingTo("14.99");
        assertThat(readAfterUpdate.getBody().stockQuantity()).isEqualTo(20);

        // Populate the list cache, then create a second product - list cache must be evicted,
        // otherwise this second product would be invisible until the TTL expires.
        Map<?, ?> firstPage = restTemplate.getForObject(baseUrl() + "?page=0&size=20", Map.class);
        int countBeforeSecondCreate = ((java.util.List<?>) firstPage.get("content")).size();

        restTemplate.postForEntity(baseUrl(),
                new HttpEntity<>(new CreateProductRequest("Gadget", "A gadget", new BigDecimal("4.99"), 3), adminHeaders()),
                ProductResponse.class);

        Map<?, ?> pageAfterSecondCreate = restTemplate.getForObject(baseUrl() + "?page=0&size=20", Map.class);
        int countAfterSecondCreate = ((java.util.List<?>) pageAfterSecondCreate.get("content")).size();
        assertThat(countAfterSecondCreate).isEqualTo(countBeforeSecondCreate + 1);

        // Delete (soft) must evict both the entry and the list - deleted product must vanish from both.
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                baseUrl() + "/" + created.id(), HttpMethod.DELETE, new HttpEntity<>(adminHeaders()), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> readAfterDelete = restTemplate.getForEntity(baseUrl() + "/" + created.id(), String.class);
        assertThat(readAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<?, ?> pageAfterDelete = restTemplate.getForObject(baseUrl() + "?page=0&size=20", Map.class);
        int countAfterDelete = ((java.util.List<?>) pageAfterDelete.get("content")).size();
        assertThat(countAfterDelete).isEqualTo(countAfterSecondCreate - 1);
    }

    @Test
    void create_rejectsRequestWithoutToken() {
        CreateProductRequest request = new CreateProductRequest("Widget", "desc", new BigDecimal("9.99"), 10);
        ResponseEntity<String> response = restTemplate.postForEntity(baseUrl(), request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void create_rejectsCustomerRole_withForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtFactory.customerToken());
        CreateProductRequest request = new CreateProductRequest("Widget", "desc", new BigDecimal("9.99"), 10);

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl(), new HttpEntity<>(request, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getById_returnsNotFound_forUnknownProduct() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/" + java.util.UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_rejectsInvalidPayload_withBadRequest() {
        CreateProductRequest invalid = new CreateProductRequest("", null, new BigDecimal("-1"), -5);
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl(), new HttpEntity<>(invalid, adminHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
