package com.orderflow.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateStockRequest(
        @NotNull UUID productId,
        @NotNull @Min(0) Integer availableQty) {
}
