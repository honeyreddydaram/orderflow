package com.orderflow.inventory.dto;

import jakarta.validation.constraints.NotNull;

public record AdjustStockRequest(@NotNull Integer adjustment) {
}
