package com.orderflow.payment.repository;

import com.orderflow.payment.domain.OutboxEvent;
import com.orderflow.payment.domain.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PaymentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void findByOrderId_returnsSavedPayment() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.completed(orderId, UUID.randomUUID(), new BigDecimal("29.97"), "TXN-1");
        paymentRepository.save(payment);

        var found = paymentRepository.findByOrderId(orderId);

        assertThat(found).isPresent();
        assertThat(found.get().getTransactionRef()).isEqualTo("TXN-1");
    }

    @Test
    void orderId_isUniqueConstrained() {
        UUID orderId = UUID.randomUUID();
        paymentRepository.saveAndFlush(Payment.completed(orderId, UUID.randomUUID(), new BigDecimal("9.99"), "TXN-1"));

        Payment duplicate = Payment.completed(orderId, UUID.randomUUID(), new BigDecimal("19.99"), "TXN-2");
        // saveAndFlush (not save + a raw entityManager.flush()) is required here: Spring's
        // persistence-exception translation only intercepts calls made through the repository
        // proxy, not direct EntityManager calls, so a raw flush() would surface Hibernate's
        // untranslated ConstraintViolationException instead.
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxEventRepository_findUnpublished_excludesPublishedRows() {
        OutboxEvent unpublished = OutboxEvent.create(UUID.randomUUID(), "PaymentCompleted", "payment.completed", "{}", UUID.randomUUID());
        OutboxEvent published = OutboxEvent.create(UUID.randomUUID(), "PaymentCompleted", "payment.completed", "{}", UUID.randomUUID());
        published.markPublished();
        outboxEventRepository.save(unpublished);
        outboxEventRepository.save(published);

        var result = outboxEventRepository.findUnpublished();

        assertThat(result).extracting(OutboxEvent::getId).containsExactly(unpublished.getId());
    }

    @Test
    void processedEventRepository_insertIfAbsent_isConflictSafe() {
        UUID eventId = UUID.randomUUID();

        int firstInsert = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        int secondInsert = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(0);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }
}
