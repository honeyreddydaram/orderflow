package com.orderflow.order.dto;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/** Lightweight payload for frontend status polling - avoids shipping the full item list. */
public record OrderStatusResponse(UUID orderId, OrderStatus status, Instant updatedAt) {

    public static OrderStatusResponse from(Order order) {
        return new OrderStatusResponse(order.getId(), order.getStatus(), order.getUpdatedAt());
    }
}
