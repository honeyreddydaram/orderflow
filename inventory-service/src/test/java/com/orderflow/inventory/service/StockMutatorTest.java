package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.StockItem;
import com.orderflow.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMutatorTest {

    @Mock
    private StockItemRepository stockItemRepository;

    @InjectMocks
    private StockMutator stockMutator;

    private static StockItem stockOf(UUID productId, int available, int reserved, long version) {
        StockItem item = StockItem.create(productId, available);
        item.setReservedQty(reserved);
        item.setVersion(version);
        return item;
    }

    @Test
    void reserve_succeedsOnFirstAttempt() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 10, 0, 5)));
        when(stockItemRepository.tryReserve(productId, 3, 5)).thenReturn(1);

        StockMutator.Result result = stockMutator.reserve(productId, 3);

        assertThat(result).isEqualTo(StockMutator.Result.SUCCESS);
    }

    @Test
    void reserve_retriesOnVersionConflictThenSucceeds() {
        UUID productId = UUID.randomUUID();
        // First read sees version 5 and fails (someone else updated concurrently); second read
        // sees the new version 6 and succeeds.
        when(stockItemRepository.findById(productId))
                .thenReturn(Optional.of(stockOf(productId, 10, 0, 5)))
                .thenReturn(Optional.of(stockOf(productId, 10, 0, 6)));
        when(stockItemRepository.tryReserve(productId, 3, 5)).thenReturn(0);
        when(stockItemRepository.tryReserve(productId, 3, 6)).thenReturn(1);

        StockMutator.Result result = stockMutator.reserve(productId, 3);

        assertThat(result).isEqualTo(StockMutator.Result.SUCCESS);
        verify(stockItemRepository, times(2)).findById(productId);
    }

    @Test
    void reserve_failsFast_whenGenuinelyInsufficientStock() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 2, 0, 5)));

        StockMutator.Result result = stockMutator.reserve(productId, 3);

        assertThat(result).isEqualTo(StockMutator.Result.INSUFFICIENT);
        verify(stockItemRepository, times(0)).tryReserve(eq(productId), eq(3), anyLong());
    }

    @Test
    void reserve_returnsInsufficient_whenRetriesExhaustedUnderContention() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 10, 0, 5)));
        when(stockItemRepository.tryReserve(eq(productId), eq(3), anyLong())).thenReturn(0);

        StockMutator.Result result = stockMutator.reserve(productId, 3);

        assertThat(result).isEqualTo(StockMutator.Result.INSUFFICIENT);
        verify(stockItemRepository, times(3)).tryReserve(eq(productId), eq(3), anyLong());
    }

    @Test
    void reserve_returnsNotFound_whenNoStockRow() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.empty());

        assertThat(stockMutator.reserve(productId, 1)).isEqualTo(StockMutator.Result.NOT_FOUND);
    }

    @Test
    void decrementReserved_blocked_whenReservedQtyInsufficient() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 0, 2, 5)));

        StockMutator.Result result = stockMutator.decrementReserved(productId, 5);

        assertThat(result).isEqualTo(StockMutator.Result.INSUFFICIENT);
        verify(stockItemRepository, times(0)).tryDecrementReserved(eq(productId), eq(5), anyLong());
    }

    @Test
    void release_succeeds() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 0, 5, 7)));
        when(stockItemRepository.tryRelease(productId, 5, 7)).thenReturn(1);

        assertThat(stockMutator.release(productId, 5)).isEqualTo(StockMutator.Result.SUCCESS);
    }

    @Test
    void adjust_blocked_whenWouldGoNegative() {
        UUID productId = UUID.randomUUID();
        when(stockItemRepository.findById(productId)).thenReturn(Optional.of(stockOf(productId, 3, 0, 1)));

        StockMutator.Result result = stockMutator.adjust(productId, -5);

        assertThat(result).isEqualTo(StockMutator.Result.INSUFFICIENT);
        verify(stockItemRepository, times(0)).tryAdjust(eq(productId), eq(-5), anyLong());
    }
}
