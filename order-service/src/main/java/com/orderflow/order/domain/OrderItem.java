package com.orderflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Snapshot of a product at order-creation time - name/price are copied, never a live reference,
 * so this order remains historically accurate even if the product later changes or is removed.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    public static OrderItem of(Order order, UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.id = UUID.randomUUID();
        item.order = order;
        item.productId = productId;
        item.productName = productName;
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        item.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return item;
    }
}
