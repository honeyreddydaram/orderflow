package com.orderflow.order.repository;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.OutboxEvent;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void findAllByUserId_paginatesAndExcludesOtherUsers() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            orderRepository.save(orderWithItem(userId));
        }
        orderRepository.save(orderWithItem(UUID.randomUUID()));

        Page<Order> page = orderRepository.findAllByUserId(userId, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void outboxEventRepository_findUnpublished_excludesPublishedRows() {
        OutboxEvent unpublished = OutboxEvent.create(UUID.randomUUID(), "OrderCreated", "order.created", "{}", UUID.randomUUID());
        OutboxEvent published = OutboxEvent.create(UUID.randomUUID(), "OrderCreated", "order.created", "{}", UUID.randomUUID());
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

    private static Order orderWithItem(UUID userId) {
        Order order = Order.create(userId);
        order.addItem(OrderItem.of(order, UUID.randomUUID(), "Widget", new BigDecimal("9.99"), 1));
        return order;
    }
}
