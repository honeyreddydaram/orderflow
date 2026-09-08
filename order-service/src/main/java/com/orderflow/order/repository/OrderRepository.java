package com.orderflow.order.repository;

import com.orderflow.order.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findAllByUserId(UUID userId, Pageable pageable);

    /**
     * Eagerly fetches items in the same query, so the result is safe to read (including
     * order.getItems()) even outside an active transaction - unlike plain findById(), whose
     * lazy-loaded items collection throws LazyInitializationException once the entity is
     * detached. Used wherever a caller maps straight to a response DTO without its own
     * surrounding @Transactional method, e.g. the idempotency-key replay path in OrderService.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(UUID id);
}
