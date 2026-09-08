package com.orderflow.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.order.TestJwtFactory;
import com.orderflow.order.client.ProductDto;
import com.orderflow.order.client.ProductServiceClient;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.order.messaging.event.InventoryReservedPayload;
import com.orderflow.order.messaging.event.PaymentCompletedPayload;
import com.orderflow.order.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderControllerIntegrationTest {

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
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private ProductServiceClient productServiceClient;

    private KafkaTemplate<String, String> testProducer;

    private KafkaTemplate<String, String> testProducer() {
        if (testProducer == null) {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            testProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
        return testProducer;
    }

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/orders";
    }

    private HttpHeaders authHeaders(UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtFactory.customerToken(userId));
        return headers;
    }

    private void stubProduct(UUID productId, String name, String price) {
        when(productServiceClient.getProduct(productId)).thenReturn(new ProductDto(productId, name, new BigDecimal(price)));
    }

    private OrderResponse createOrder(UUID userId, UUID productId, int quantity) {
        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, quantity)));
        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
                baseUrl(), new HttpEntity<>(request, authHeaders(userId)), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private OrderResponse getOrder(UUID userId, UUID orderId) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                baseUrl() + "/" + orderId, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders(userId)), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> void publish(String topic, UUID orderId, String eventType, T payload, UUID eventId) {
        EventEnvelope<T> envelope = new EventEnvelope<>(eventId, eventType, UUID.randomUUID(), Instant.now(), payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            testProducer().send(topic, orderId.toString(), json).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitTrue(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    @Test
    void fullOrderLifecycle_happyPath_progressesToConfirmed() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99");

        OrderResponse created = createOrder(userId, productId, 2);
        assertThat(created.status()).isEqualTo(OrderStatus.PENDING);

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(created.id()) && e.getEventType().equals("OrderCreated")
                        && e.getPublishedAt() != null), Duration.ofSeconds(10));

        publish("inventory.reserved", created.id(), "InventoryReserved",
                new InventoryReservedPayload(created.id(), UUID.randomUUID(), List.of()), UUID.randomUUID());
        awaitTrue(() -> getOrder(userId, created.id()).status() == OrderStatus.AWAITING_PAYMENT, Duration.ofSeconds(10));

        publish("payment.completed", created.id(), "PaymentCompleted",
                new PaymentCompletedPayload(created.id(), UUID.randomUUID(), created.totalAmount(), "txn-1"), UUID.randomUUID());
        awaitTrue(() -> getOrder(userId, created.id()).status() == OrderStatus.CONFIRMED, Duration.ofSeconds(10));

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(created.id()) && e.getEventType().equals("OrderConfirmed")
                        && e.getPublishedAt() != null), Duration.ofSeconds(10));
    }

    @Test
    void inventoryReservationFailed_movesOrderToFailed() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99");

        OrderResponse created = createOrder(userId, productId, 1);

        publish("inventory.reservation-failed", created.id(), "InventoryReservationFailed",
                new InventoryReservationFailedPayload(created.id(), "Out of stock", List.of()), UUID.randomUUID());

        awaitTrue(() -> getOrder(userId, created.id()).status() == OrderStatus.FAILED, Duration.ofSeconds(10));
    }

    @Test
    void duplicatePaymentCompletedDelivery_transitionsExactlyOnce() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99");

        OrderResponse created = createOrder(userId, productId, 1);
        publish("inventory.reserved", created.id(), "InventoryReserved",
                new InventoryReservedPayload(created.id(), UUID.randomUUID(), List.of()), UUID.randomUUID());
        awaitTrue(() -> getOrder(userId, created.id()).status() == OrderStatus.AWAITING_PAYMENT, Duration.ofSeconds(10));

        UUID paymentEventId = UUID.randomUUID();
        PaymentCompletedPayload payload = new PaymentCompletedPayload(created.id(), UUID.randomUUID(), created.totalAmount(), "txn-1");
        publish("payment.completed", created.id(), "PaymentCompleted", payload, paymentEventId);
        awaitTrue(() -> getOrder(userId, created.id()).status() == OrderStatus.CONFIRMED, Duration.ofSeconds(10));

        // Redeliver the identical event id - must not double-transition or double-publish.
        publish("payment.completed", created.id(), "PaymentCompleted", payload, paymentEventId);
        // Give the (no-op) redelivery a moment to be handled before asserting nothing changed.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(getOrder(userId, created.id()).status()).isEqualTo(OrderStatus.CONFIRMED);
        long confirmedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(created.id()) && e.getEventType().equals("OrderConfirmed"))
                .count();
        assertThat(confirmedOutboxRows).isEqualTo(1);
    }

    @Test
    void idempotencyKey_replayReturnsOriginalOrder_withoutCreatingDuplicate() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99");

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 1)));
        HttpHeaders headers = authHeaders(userId);
        headers.add("Idempotency-Key", "replay-key-1");

        ResponseEntity<OrderResponse> first = restTemplate.postForEntity(baseUrl(), new HttpEntity<>(request, headers), OrderResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<OrderResponse> second = restTemplate.postForEntity(baseUrl(), new HttpEntity<>(request, headers), OrderResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
    }
}
