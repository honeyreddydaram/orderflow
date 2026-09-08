package com.orderflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per product - product_id is used directly as the primary key since the relationship is
 * inherently 1:1, no surrogate id needed. Mutations never go through plain JPA save() for
 * available_qty/reserved_qty; they go through StockItemRepository's conditional bulk UPDATE
 * queries (see docs/architecture.md section 9) so the optimistic-lock + invariant checks happen
 * atomically in the database, not via a read-modify-write in application code.
 */
@Entity
@Table(name = "stock_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockItem {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static StockItem create(UUID productId, int availableQty) {
        StockItem item = new StockItem();
        item.productId = productId;
        item.availableQty = availableQty;
        item.reservedQty = 0;
        item.version = 0;
        return item;
    }
}
