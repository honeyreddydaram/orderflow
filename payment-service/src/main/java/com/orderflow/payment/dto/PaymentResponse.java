package com.orderflow.payment.dto;

import com.orderflow.payment.domain.Payment;
import com.orderflow.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID userId,
        BigDecimal amount,
        PaymentStatus status,
        String transactionRef,
        String declineReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getUserId(), payment.getAmount(),
                payment.getStatus(), payment.getTransactionRef(), payment.getDeclineReason(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
