package com.orderflow.payment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.payment.domain.PaymentStatus;
import com.orderflow.payment.messaging.event.InventoryReservedPayload;
import com.orderflow.payment.repository.OutboxEventRepository;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.repository.ProcessedEventRepository;
import com.orderflow.payment.service.OutboxWriter;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class InventoryReservedEventListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
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
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @SpyBean
    private OutboxWriter outboxWriter;
    @Autowired
    private PlatformTransactionManager transactionManager;

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

    private static InventoryReservedPayload reservedPayload(UUID orderId, BigDecimal totalAmount) {
        return new InventoryReservedPayload(orderId, UUID.randomUUID(), UUID.randomUUID(), totalAmount, List.of());
    }

    @Test
    void inventoryReserved_approvesPayment_whenAmountUnderThreshold() {
        UUID orderId = UUID.randomUUID();
        publish("inventory.reserved", orderId, "InventoryReserved",
                reservedPayload(orderId, new BigDecimal("29.97")), UUID.randomUUID());

        awaitTrue(() -> paymentRepository.findByOrderId(orderId).isPresent(), Duration.ofSeconds(15));
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getTransactionRef()).isNotBlank();

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("PaymentCompleted")
                        && e.getPublishedAt() != null), Duration.ofSeconds(15));
    }

    @Test
    void inventoryReserved_declinesPayment_whenAmountOverThreshold() {
        UUID orderId = UUID.randomUUID();
        publish("inventory.reserved", orderId, "InventoryReserved",
                reservedPayload(orderId, new BigDecimal("15000.00")), UUID.randomUUID());

        awaitTrue(() -> paymentRepository.findByOrderId(orderId).isPresent(), Duration.ofSeconds(15));
        var payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getDeclineReason()).isNotBlank();

        awaitTrue(() -> outboxEventRepository.findAll().stream()
                .anyMatch(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("PaymentFailed")
                        && e.getPublishedAt() != null), Duration.ofSeconds(15));
    }

    @Test
    void duplicateInventoryReservedDelivery_processesExactlyOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var payload = reservedPayload(orderId, new BigDecimal("29.97"));

        publish("inventory.reserved", orderId, "InventoryReserved", payload, eventId);
        awaitTrue(() -> paymentRepository.findByOrderId(orderId).isPresent(), Duration.ofSeconds(15));

        // Redeliver the identical event id.
        publish("inventory.reserved", orderId, "InventoryReserved", payload, eventId);
        sleep();
        sleep();

        assertThat(paymentRepository.findAll().stream().filter(p -> p.getOrderId().equals(orderId)).count())
                .isEqualTo(1);
        long completedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("PaymentCompleted"))
                .count();
        assertThat(completedOutboxRows).isEqualTo(1);
    }

    /**
     * The correctness proof for the transaction-boundary pattern established in Inventory
     * Service: simulate a crash AFTER the processed_events marker would have been written but
     * BEFORE the transaction commits, by making the last write in the handler (the outbox write)
     * throw once. The whole transaction - including the processed_events insert and the Payment
     * row - must roll back, Spring Kafka must redeliver, and the retry must complete the payment
     * exactly once: not lost, not double-applied. Fault injection targets the concrete
     * OutboxWriter.write, never a Spring Data repository method - see docs/architecture.md
     * section 12 for why doCallRealMethod() can't be used on an abstract repository method.
     */
    @Test
    void paymentSurvivesATransientFailure_beforeCommit_viaKafkaRetry() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Mockito.doThrow(new RuntimeException("simulated crash before commit"))
                .doCallRealMethod()
                .when(outboxWriter).write(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        publish("inventory.reserved", orderId, "InventoryReserved",
                reservedPayload(orderId, new BigDecimal("29.97")), eventId);

        // First delivery attempt fails mid-transaction; Spring Kafka's error handler retries with
        // backoff (500ms, x2) - allow enough time for at least one retry to land.
        awaitTrue(() -> paymentRepository.findByOrderId(orderId).isPresent(), Duration.ofSeconds(20));

        assertThat(paymentRepository.findAll().stream().filter(p -> p.getOrderId().equals(orderId)).count())
                .isEqualTo(1);
        long completedOutboxRows = outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId) && e.getEventType().equals("PaymentCompleted"))
                .count();
        assertThat(completedOutboxRows).isEqualTo(1);

        // insertIfAbsent returning 0 here means the marker is already durably recorded (the
        // successful retry's transaction committed it) - if the transaction-boundary were wrong
        // and the marker had been committed separately from the business work, the first
        // (failing) attempt would have left it stuck at "processed" and this would return 1.
        Integer insertedAgain = new TransactionTemplate(transactionManager)
                .execute(status -> processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId));
        assertThat(insertedAgain).isEqualTo(0);
    }
}
