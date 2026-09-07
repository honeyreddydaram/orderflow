package com.orderflow.product.dto;

import com.orderflow.product.domain.Product;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements Serializable because the default Redis cache serializer needs the whole cached
 * object graph to round-trip through Java's ObjectOutputStream is NOT used here (we configure a
 * JSON serializer in CacheConfig) - kept for defensive compatibility if that ever changes.
 */
public record ProductResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        Instant createdAt,
        Instant updatedAt) implements Serializable {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
