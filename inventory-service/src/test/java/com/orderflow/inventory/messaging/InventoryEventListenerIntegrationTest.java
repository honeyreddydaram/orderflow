package com.orderflow.inventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.inventory.messaging.event.InventoryReservedPayload;
import com.orderflow.inventory.messaging.event.OrderCreatedPayload;
import com.orderflow.inventory.messaging.event.PaymentCompletedPayload;
import com.orderflow.inventory.messaging.event.PaymentFailedPayload;
import com.orderflow.inventory.repository.OutboxEventRepository;
import com.orderflow.inventory.repository.ProcessedEventRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.repository.StockItemRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InventoryEventListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private StockItemRepository stockItemRepository;
    @SpyBean
    private ReservationRepository reservationRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private KafkaTemplate<String, String> testProducer;
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

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
            sleep();
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private static void sleep() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private UUID seedStock(int availableQty) {
        UUID productId = UUID.randomUUID();
        stockItemRepository.saveAndFlush(StockItem.create(productId, availableQty));
        return productId;
    }

    @Test
    void orderCreated_reservesStock_whenSufficient() {
        UUID productId = seedStock(10);
        UUID orderId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, UUID.randomUUID(),
                List.of(new OrderCreatedPayload.Item(productId, 3, new BigDecimal("9.99"))), new BigDecimal("29.97"));

        publish("order.created", orderId, "OrderCreated", payload, UUID.randomUUID());

        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId).isPresent(), Duration.ofSeconds(15));
        var reservation = reservationRepository.findByOrderIdWithItems(orderId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);

        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        assertThat(stock.getAvailableQty()).isEqualTo(7);
        assertThat(stock.getReservedQty()).isEqualTo(3);

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("InventoryReserved")
                        && e.getPublishedAt() != null), Duration.ofSeconds(15));
    }

    @Test
    void orderCreated_publishesFailure_whenInsufficientStock_andReservesNothing() {
        UUID productId = seedStock(2);
        UUID orderId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, UUID.randomUUID(),
                List.of(new OrderCreatedPayload.Item(productId, 5, new BigDecimal("9.99"))), new BigDecimal("49.95"));

        publish("order.created", orderId, "OrderCreated", payload, UUID.randomUUID());

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("InventoryReservationFailed")
                        && e.getPublishedAt() != null), Duration.ofSeconds(15));

        assertThat(reservationRepository.findByOrderIdWithItems(orderId)).isEmpty();
        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        assertThat(stock.getAvailableQty()).isEqualTo(2);
        assertThat(stock.getReservedQty()).isEqualTo(0);
    }

    @Test
    void orderCreated_allOrNothing_whenOneOfSeveralItemsInsufficient() {
        UUID plentifulProduct = seedStock(10);
        UUID scarceProduct = seedStock(1);
        UUID orderId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, UUID.randomUUID(), List.of(
                new OrderCreatedPayload.Item(plentifulProduct, 5, new BigDecimal("9.99")),
                new OrderCreatedPayload.Item(scarceProduct, 5, new BigDecimal("4.99"))),
                new BigDecimal("74.90"));

        publish("order.created", orderId, "OrderCreated", payload, UUID.randomUUID());

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("InventoryReservationFailed")),
                Duration.ofSeconds(15));

        // The plentiful product's reservation must have been rolled back too - all or nothing.
        StockItem plentifulStock = stockItemRepository.findById(plentifulProduct).orElseThrow();
        assertThat(plentifulStock.getAvailableQty()).isEqualTo(10);
        assertThat(plentifulStock.getReservedQty()).isEqualTo(0);
        assertThat(reservationRepository.findByOrderIdWithItems(orderId)).isEmpty();
    }

    @Test
    void duplicateOrderCreatedDelivery_reservesExactlyOnce() {
        UUID productId = seedStock(10);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, UUID.randomUUID(),
                List.of(new OrderCreatedPayload.Item(productId, 3, new BigDecimal("9.99"))), new BigDecimal("29.97"));

        publish("order.created", orderId, "OrderCreated", payload, eventId);
        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId).isPresent(), Duration.ofSeconds(15));

        // Redeliver the identical event id.
        publish("order.created", orderId, "OrderCreated", payload, eventId);
        sleep();
        sleep();

        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        assertThat(stock.getAvailableQty()).isEqualTo(7);
        assertThat(stock.getReservedQty()).isEqualTo(3);
        long reservedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("InventoryReserved"))
                .count();
        assertThat(reservedOutboxRows).isEqualTo(1);
    }

    @Test
    void paymentCompleted_permanentlyDecrementsReservedStock() {
        UUID productId = seedStock(10);
        UUID orderId = UUID.randomUUID();
        publish("order.created", orderId, "OrderCreated",
                new OrderCreatedPayload(orderId, UUID.randomUUID(),
                        List.of(new OrderCreatedPayload.Item(productId, 4, new BigDecimal("9.99"))), new BigDecimal("39.96")),
                UUID.randomUUID());
        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId).isPresent(), Duration.ofSeconds(15));

        publish("payment.completed", orderId, "PaymentCompleted",
                new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("39.96"), "txn-1"), UUID.randomUUID());

        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId)
                .map(r -> r.getStatus() == ReservationStatus.DECREMENTED).orElse(false), Duration.ofSeconds(15));

        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        assertThat(stock.getAvailableQty()).isEqualTo(6);
        assertThat(stock.getReservedQty()).isEqualTo(0);
    }

    @Test
    void paymentFailed_releasesReservedStockBackToAvailable() {
        UUID productId = seedStock(10);
        UUID orderId = UUID.randomUUID();
        publish("order.created", orderId, "OrderCreated",
                new OrderCreatedPayload(orderId, UUID.randomUUID(),
                        List.of(new OrderCreatedPayload.Item(productId, 4, new BigDecimal("9.99"))), new BigDecimal("39.96")),
                UUID.randomUUID());
        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId).isPresent(), Duration.ofSeconds(15));

        publish("payment.failed", orderId, "PaymentFailed", new PaymentFailedPayload(orderId, "Card declined"), UUID.randomUUID());

        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId)
                .map(r -> r.getStatus() == ReservationStatus.RELEASED).orElse(false), Duration.ofSeconds(15));

        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        assertThat(stock.getAvailableQty()).isEqualTo(10);
        assertThat(stock.getReservedQty()).isEqualTo(0);
    }

    /**
     * The correctness proof for the transaction-boundary fix: simulate a crash AFTER the
     * processed_events marker would have been written but BEFORE the transaction commits, by
     * making the very last write in the reservation transaction (Reservation.save) throw once.
     * The whole transaction - including the processed_events insert - must roll back, Spring
     * Kafka must redeliver, and the retry must complete the reservation exactly once: not lost,
     * not double-applied.
     */
    @Test
    void reservationSurvivesATransientFailure_beforeCommit_viaKafkaRetry() {
        UUID productId = seedStock(10);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Mockito.doThrow(new RuntimeException("simulated crash before commit"))
                .doCallRealMethod()
                .when(reservationRepository).save(Mockito.any());

        publish("order.created", orderId, "OrderCreated",
                new OrderCreatedPayload(orderId, UUID.randomUUID(),
                        List.of(new OrderCreatedPayload.Item(productId, 3, new BigDecimal("9.99"))), new BigDecimal("29.97")),
                eventId);

        // First delivery attempt fails mid-transaction; Spring Kafka's error handler retries with
        // backoff (500ms, x2) - allow enough time for at least one retry to land.
        awaitTrue(() -> reservationRepository.findByOrderIdWithItems(orderId).isPresent(), Duration.ofSeconds(20));

        StockItem stock = stockItemRepository.findById(productId).orElseThrow();
        // Exactly one reservation's worth of stock was moved - not zero (lost), not six (double-applied).
        assertThat(stock.getAvailableQty()).isEqualTo(7);
        assertThat(stock.getReservedQty()).isEqualTo(3);

        long reservedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("InventoryReserved"))
                .count();
        assertThat(reservedOutboxRows).isEqualTo(1);

        // insertIfAbsent returning 0 here means the marker is already durably recorded (the
        // successful retry's transaction committed it) - if the transaction-boundary fix were
        // wrong and the marker had been committed separately from the business work, the first
        // (failing) attempt would have left it stuck at "processed" and this would return 1.
        assertThat(processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId)).isEqualTo(0);
    }
}
