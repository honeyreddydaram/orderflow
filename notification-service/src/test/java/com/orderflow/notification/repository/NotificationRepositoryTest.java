package com.orderflow.notification.repository;

import com.orderflow.notification.domain.Notification;
import com.orderflow.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class NotificationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void findAllByUserId_paginatesAndExcludesOtherUsers() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            notificationRepository.save(confirmedNotification(userId));
        }
        notificationRepository.save(confirmedNotification(UUID.randomUUID()));

        Page<Notification> page = notificationRepository.findAllByUserId(userId, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
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

    private static Notification confirmedNotification(UUID userId) {
        UUID orderId = UUID.randomUUID();
        return Notification.create(orderId, userId, NotificationType.ORDER_CONFIRMED,
                "Your order has been confirmed", "Order " + orderId + " has been confirmed. Total: 9.99.");
    }
}
