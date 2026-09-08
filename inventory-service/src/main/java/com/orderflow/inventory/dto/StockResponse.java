package com.orderflow.inventory.dto;

import com.orderflow.inventory.domain.StockItem;

import java.time.Instant;
import java.util.UUID;

public record StockResponse(
        UUID productId,
        int availableQty,
        int reservedQty,
        Instant createdAt,
        Instant updatedAt) {

    public static StockResponse from(StockItem item) {
        return new StockResponse(item.getProductId(), item.getAvailableQty(), item.getReservedQty(),
                item.getCreatedAt(), item.getUpdatedAt());
    }
}
