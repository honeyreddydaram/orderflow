package com.orderflow.order.domain;

public enum OrderStatus {
    PENDING,
    AWAITING_PAYMENT,
    CONFIRMED,
    FAILED,
    /** No code path produces this yet - reserved for a future cancel-order feature. */
    CANCELLED
}
