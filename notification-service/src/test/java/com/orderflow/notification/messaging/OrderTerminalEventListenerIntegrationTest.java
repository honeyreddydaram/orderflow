package com.orderflow.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.notification.domain.NotificationType;
import com.orderflow.notification.messaging.event.OrderConfirmedPayload;
import com.orderflow.notification.messaging.event.OrderFailedPayload;
import com.orderflow.notification.repository.NotificationRepository;
import com.orderflow.notification.repository.ProcessedEventRepository;
import com.orderflow.notification.service.NotificationSender;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OrderTerminalEventListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_db")
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
    private NotificationRepository notificationRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @SpyBean
    private NotificationSender notificationSender;
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

    @Test
    void orderConfirmed_createsConfirmationNotification() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        publish("order.confirmed", orderId, "OrderConfirmed",
                new OrderConfirmedPayload(orderId, userId, new BigDecimal("29.97")), UUID.randomUUID());

        awaitTrue(() -> !notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).isEmpty(),
                Duration.ofSeconds(15));

        var notification = notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(notification.getOrderId()).isEqualTo(orderId);
    }

    @Test
    void orderFailed_createsFailureNotification() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        publish("order.failed", orderId, "OrderFailed",
                new OrderFailedPayload(orderId, userId, "Card declined"), UUID.randomUUID());

        awaitTrue(() -> !notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).isEmpty(),
                Duration.ofSeconds(15));

        var notification = notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_FAILED);
        assertThat(notification.getMessage()).contains("Card declined");
    }

    @Test
    void duplicateOrderConfirmedDelivery_processesExactlyOnce() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var payload = new OrderConfirmedPayload(orderId, userId, new BigDecimal("29.97"));

        publish("order.confirmed", orderId, "OrderConfirmed", payload, eventId);
        awaitTrue(() -> !notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).isEmpty(),
                Duration.ofSeconds(15));

        // Redeliver the identical event id.
        publish("order.confirmed", orderId, "OrderConfirmed", payload, eventId);
        sleep();
        sleep();

        assertThat(notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements())
                .isEqualTo(1);
    }

    /**
     * The correctness proof for the transaction-boundary pattern established in Inventory/Payment
     * Service: simulate a crash AFTER the processed_events marker would have been written but
     * BEFORE the transaction commits, by making the last write in the handler (the simulated send)
     * throw once. The whole transaction - including the processed_events insert and the
     * Notification row - must roll back, Spring Kafka must redeliver, and the retry must complete
     * the notification exactly once: not lost, not double-applied. Fault injection targets the
     * concrete NotificationSender.send, never a Spring Data repository method - see
     * docs/architecture.md section 12 for why doCallRealMethod() can't be used on an abstract
     * repository method.
     */
    @Test
    void notificationSurvivesATransientFailure_beforeCommit_viaKafkaRetry() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Mockito.doThrow(new RuntimeException("simulated crash before commit"))
                .doCallRealMethod()
                .when(notificationSender).send(Mockito.any(), Mockito.any());

        publish("order.confirmed", orderId, "OrderConfirmed",
                new OrderConfirmedPayload(orderId, userId, new BigDecimal("29.97")), eventId);

        // First delivery attempt fails mid-transaction; Spring Kafka's error handler retries with
        // backoff (500ms, x2) - allow enough time for at least one retry to land.
        awaitTrue(() -> !notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).isEmpty(),
                Duration.ofSeconds(20));

        assertThat(notificationRepository.findAllByUserId(userId, org.springframework.data.domain.Pageable.unpaged()).getTotalElements())
                .isEqualTo(1);

        // insertIfAbsent returning 0 here means the marker is already durably recorded (the
        // successful retry's transaction committed it) - if the transaction-boundary were wrong
        // and the marker had been committed separately from the business work, the first
        // (failing) attempt would have left it stuck at "processed" and this would return 1.
        Integer insertedAgain = new TransactionTemplate(transactionManager)
                .execute(status -> processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId));
        assertThat(insertedAgain).isEqualTo(0);
    }
}
