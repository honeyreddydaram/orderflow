package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Every stock mutation (reserve, decrement, release, manual adjust) follows the same shape: a
 * conditional bulk UPDATE guarded by both the optimistic-lock version and a business invariant
 * (never oversell, never let reserved go negative - see docs/architecture.md section 9), retried
 * a bounded number of times on version conflicts. This class is the one place that shape lives;
 * StockItemRepository just holds the four raw queries.
 */
@Component
public class StockMutator {

    private static final int MAX_ATTEMPTS = 3;

    public enum Result {
        SUCCESS,
        /** Genuine invariant violation, or version-conflict retries exhausted under contention. */
        INSUFFICIENT,
        NOT_FOUND
    }

    private final StockItemRepository stockItemRepository;

    public StockMutator(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    public Result reserve(UUID productId, int qty) {
        return mutate(productId, current -> current.getAvailableQty() >= qty,
                version -> stockItemRepository.tryReserve(productId, qty, version));
    }

    public Result decrementReserved(UUID productId, int qty) {
        return mutate(productId, current -> current.getReservedQty() >= qty,
                version -> stockItemRepository.tryDecrementReserved(productId, qty, version));
    }

    public Result release(UUID productId, int qty) {
        return mutate(productId, current -> current.getReservedQty() >= qty,
                version -> stockItemRepository.tryRelease(productId, qty, version));
    }

    public Result adjust(UUID productId, int delta) {
        return mutate(productId, current -> current.getAvailableQty() + delta >= 0,
                version -> stockItemRepository.tryAdjust(productId, delta, version));
    }

    /**
     * Re-reads current state each attempt (needed to get the latest version to retry with, and
     * to tell a version conflict from a genuine invariant violation) and applies {@code attempt}
     * against it; on 0 rows affected, loops. The actual correctness guarantee comes from the SQL
     * guard in the UPDATE itself, not from this pre-check - the pre-check only decides whether
     * retrying is worth attempting.
     */
    private Result mutate(UUID productId, Predicate<StockItem> stillValid, ToIntFunction<Long> attempt) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            StockItem current = stockItemRepository.findById(productId).orElse(null);
            if (current == null) {
                return Result.NOT_FOUND;
            }
            if (!stillValid.test(current)) {
                return Result.INSUFFICIENT;
            }
            int affected = attempt.applyAsInt(current.getVersion());
            if (affected > 0) {
                return Result.SUCCESS;
            }
            // 0 affected with stillValid true means a concurrent writer changed the version
            // between our read and our UPDATE - loop and retry with the now-current version.
        }
        return Result.INSUFFICIENT;
    }
}
