package com.orderflow.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per order - a payment decision is made exactly once and never mutated afterward in v1
 * (a decline is terminal, not retried), so unlike StockItem there's no concurrency/version
 * invariant machinery needed here at all.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "transaction_ref")
    private String transactionRef;

    @Column(name = "decline_reason")
    private String declineReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Payment completed(UUID orderId, UUID userId, BigDecimal amount, String transactionRef) {
        Payment payment = base(orderId, userId, amount);
        payment.status = PaymentStatus.COMPLETED;
        payment.transactionRef = transactionRef;
        return payment;
    }

    public static Payment failed(UUID orderId, UUID userId, BigDecimal amount, String declineReason) {
        Payment payment = base(orderId, userId, amount);
        payment.status = PaymentStatus.FAILED;
        payment.declineReason = declineReason;
        return payment;
    }

    private static Payment base(UUID orderId, UUID userId, BigDecimal amount) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.orderId = orderId;
        payment.userId = userId;
        payment.amount = amount;
        return payment;
    }
}
