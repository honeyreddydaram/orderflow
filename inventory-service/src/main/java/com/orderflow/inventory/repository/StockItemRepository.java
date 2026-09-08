package com.orderflow.inventory.repository;

import com.orderflow.inventory.domain.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Every stock mutation goes through one of the three conditional bulk UPDATE queries below,
 * never a plain JPA save() of availableQty/reservedQty - each combines the optimistic-lock
 * version check with a business invariant guard (never oversell, never let reserved go negative)
 * in one atomic round trip. A 0-row result means either a version conflict (safe to retry with
 * the current version) or a genuine invariant violation (not safe to retry) - callers must
 * re-read the row to tell which. See docs/architecture.md sections 9 and 12.
 */
public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    /**
     * Reserve {@code qty} units: available -> reserved. Guarded so available_qty can never go
     * negative (the classic "prevent overselling" invariant).
     */
    @Modifying
    @Query("UPDATE StockItem s SET s.availableQty = s.availableQty - :qty, s.reservedQty = s.reservedQty + :qty, "
            + "s.version = s.version + 1 "
            + "WHERE s.productId = :productId AND s.availableQty >= :qty AND s.version = :version")
    int tryReserve(@Param("productId") UUID productId, @Param("qty") int qty, @Param("version") long version);

    /**
     * Permanently decrement reserved stock on payment.completed - available_qty was already
     * reduced at reservation time, so only reserved_qty changes. Guarded so reserved_qty can
     * never go negative even under a malformed or duplicate mutation.
     */
    @Modifying
    @Query("UPDATE StockItem s SET s.reservedQty = s.reservedQty - :qty, s.version = s.version + 1 "
            + "WHERE s.productId = :productId AND s.reservedQty >= :qty AND s.version = :version")
    int tryDecrementReserved(@Param("productId") UUID productId, @Param("qty") int qty, @Param("version") long version);

    /**
     * Release a reservation on payment.failed: reserved -> available. Guarded so reserved_qty
     * can never go negative even under a malformed or duplicate mutation.
     */
    @Modifying
    @Query("UPDATE StockItem s SET s.availableQty = s.availableQty + :qty, s.reservedQty = s.reservedQty - :qty, "
            + "s.version = s.version + 1 "
            + "WHERE s.productId = :productId AND s.reservedQty >= :qty AND s.version = :version")
    int tryRelease(@Param("productId") UUID productId, @Param("qty") int qty, @Param("version") long version);

    /** Manual admin adjustment (delta may be negative), guarded so available_qty can't go negative. */
    @Modifying
    @Query("UPDATE StockItem s SET s.availableQty = s.availableQty + :delta, s.version = s.version + 1 "
            + "WHERE s.productId = :productId AND s.availableQty + :delta >= 0 AND s.version = :version")
    int tryAdjust(@Param("productId") UUID productId, @Param("delta") int delta, @Param("version") long version);
}
