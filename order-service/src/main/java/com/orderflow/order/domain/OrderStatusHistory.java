package com.orderflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private OrderStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private OrderStatus newStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public static OrderStatusHistory record(UUID orderId, OrderStatus previousStatus, OrderStatus newStatus, String reason) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.id = UUID.randomUUID();
        history.orderId = orderId;
        history.previousStatus = previousStatus;
        history.newStatus = newStatus;
        history.reason = reason;
        history.changedAt = Instant.now();
        return history;
    }
}
