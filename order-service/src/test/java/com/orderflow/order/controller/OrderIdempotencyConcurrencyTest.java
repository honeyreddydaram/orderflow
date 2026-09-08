package com.orderflow.order.controller;

import com.orderflow.order.TestJwtFactory;
import com.orderflow.order.client.ProductDto;
import com.orderflow.order.client.ProductServiceClient;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Proves the atomic Redis SETNX-based reservation in IdempotencyKeyService actually prevents two
 * concurrent POST /orders requests using the same Idempotency-Key from both creating an order -
 * a plain "check then create then write" sequence would have a TOCTOU race here. See
 * docs/architecture.md section 10 and IdempotencyKeyService's javadoc.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderIdempotencyConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 10;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private ProductServiceClient productServiceClient;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/orders";
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKey_createExactlyOneOrderAndOneOutboxEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productServiceClient.getProduct(productId)).thenReturn(new ProductDto(productId, "Widget", new BigDecimal("9.99")));

        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 1)));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtFactory.customerToken(userId));
        headers.add("Idempotency-Key", idempotencyKey);
        HttpEntity<CreateOrderRequest> entity = new HttpEntity<>(request, headers);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ResponseEntity<OrderResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return restTemplate.postForEntity(baseUrl(), entity, OrderResponse.class);
            });
        }

        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();
        for (Callable<ResponseEntity<OrderResponse>> task : tasks) {
            futures.add(executor.submit(task));
        }
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<ResponseEntity<OrderResponse>> responses = new ArrayList<>();
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Every response must succeed (201 for the winner, 200 for every replay) and all must
        // agree on the same order id - none of them may fail or see a different order.
        assertThat(responses).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED || r.getStatusCode() == HttpStatus.OK);
        Set<UUID> distinctOrderIds = responses.stream()
                .map(r -> r.getBody().id())
                .collect(Collectors.toSet());
        assertThat(distinctOrderIds).hasSize(1);

        long ordersForUser = orderRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        assertThat(ordersForUser).isEqualTo(1);

        long orderCreatedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("OrderCreated"))
                .count();
        assertThat(orderCreatedOutboxRows).isEqualTo(1);
    }
}
