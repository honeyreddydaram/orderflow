package com.orderflow.order.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Minimal projection of Product Service's ProductResponse - only what order creation needs. */
public record ProductDto(UUID id, String name, BigDecimal price) {
}
