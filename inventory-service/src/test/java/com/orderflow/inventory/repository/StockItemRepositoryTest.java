package com.orderflow.inventory.repository;

import com.orderflow.inventory.domain.StockItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class StockItemRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private StockItemRepository stockItemRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void tryReserve_succeeds_whenSufficientStockAndCorrectVersion() {
        StockItem item = stockItemRepository.saveAndFlush(StockItem.create(UUID.randomUUID(), 10));

        int affected = stockItemRepository.tryReserve(item.getProductId(), 4, item.getVersion());
        entityManager.clear();

        assertThat(affected).isEqualTo(1);
        StockItem reloaded = stockItemRepository.findById(item.getProductId()).orElseThrow();
        assertThat(reloaded.getAvailableQty()).isEqualTo(6);
        assertThat(reloaded.getReservedQty()).isEqualTo(4);
        assertThat(reloaded.getVersion()).isEqualTo(item.getVersion() + 1);
    }

    @Test
    void tryReserve_blocked_whenInsufficientStock() {
        StockItem item = stockItemRepository.saveAndFlush(StockItem.create(UUID.randomUUID(), 3));

        int affected = stockItemRepository.tryReserve(item.getProductId(), 4, item.getVersion());
        entityManager.clear();

        assertThat(affected).isEqualTo(0);
        StockItem reloaded = stockItemRepository.findById(item.getProductId()).orElseThrow();
        assertThat(reloaded.getAvailableQty()).isEqualTo(3);
        assertThat(reloaded.getReservedQty()).isEqualTo(0);
    }

    @Test
    void tryReserve_blocked_whenVersionStale() {
        StockItem item = stockItemRepository.saveAndFlush(StockItem.create(UUID.randomUUID(), 10));

        int affected = stockItemRepository.tryReserve(item.getProductId(), 4, item.getVersion() + 1);

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void tryDecrementReserved_blocked_whenReservedQtyInsufficient() {
        StockItem item = StockItem.create(UUID.randomUUID(), 10);
        item.setReservedQty(2);
        item = stockItemRepository.saveAndFlush(item);

        // Attempting to decrement more than what's actually reserved must be rejected, not drive
        // reserved_qty negative - this is the defense-in-depth guard beneath processed_events /
        // reservation-status checks.
        int affected = stockItemRepository.tryDecrementReserved(item.getProductId(), 5, item.getVersion());
        entityManager.clear();

        assertThat(affected).isEqualTo(0);
        StockItem reloaded = stockItemRepository.findById(item.getProductId()).orElseThrow();
        assertThat(reloaded.getReservedQty()).isEqualTo(2);
    }

    @Test
    void tryRelease_blocked_whenReservedQtyInsufficient() {
        StockItem item = StockItem.create(UUID.randomUUID(), 5);
        item.setReservedQty(1);
        item = stockItemRepository.saveAndFlush(item);

        int affected = stockItemRepository.tryRelease(item.getProductId(), 3, item.getVersion());
        entityManager.clear();

        assertThat(affected).isEqualTo(0);
        StockItem reloaded = stockItemRepository.findById(item.getProductId()).orElseThrow();
        assertThat(reloaded.getAvailableQty()).isEqualTo(5);
        assertThat(reloaded.getReservedQty()).isEqualTo(1);
    }

    @Test
    void tryAdjust_blocked_whenWouldGoNegative() {
        StockItem item = stockItemRepository.saveAndFlush(StockItem.create(UUID.randomUUID(), 2));

        int affected = stockItemRepository.tryAdjust(item.getProductId(), -5, item.getVersion());

        assertThat(affected).isEqualTo(0);
    }

    /**
     * The actual "prevent overselling" proof: fires concurrent reservation attempts against a
     * stock item with limited availability from real, separate threads/connections against a real
     * Postgres, and asserts exactly as many succeed as there is stock for - never more.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReservations_neverOversell() throws Exception {
        int initialAvailable = 5;
        int concurrentAttempts = 10;
        UUID productId = UUID.randomUUID();
        stockItemRepository.saveAndFlush(StockItem.create(productId, initialAvailable));

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentAttempts; i++) {
            tasks.add(() -> reserveOneWithRetry(productId));
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        AtomicInteger successCount = new AtomicInteger();
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successCount.incrementAndGet();
            }
        }

        assertThat(successCount.get()).isEqualTo(initialAvailable);

        StockItem finalState = stockItemRepository.findById(productId).orElseThrow();
        assertThat(finalState.getAvailableQty()).isEqualTo(0);
        assertThat(finalState.getReservedQty()).isEqualTo(initialAvailable);
    }

    /** Each thread needs its own retry loop against version conflicts - mirrors StockMutator's shape. */
    private boolean reserveOneWithRetry(UUID productId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StockItem current = stockItemRepository.findById(productId).orElseThrow();
            if (current.getAvailableQty() < 1) {
                return false;
            }
            int affected = stockItemRepository.tryReserve(productId, 1, current.getVersion());
            if (affected > 0) {
                return true;
            }
        }
        return false;
    }
}
