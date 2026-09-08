package com.orderflow.order.service;

import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.common.event.EventEnvelope;
import com.orderflow.order.client.ProductDto;
import com.orderflow.order.client.ProductServiceClient;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.order.messaging.event.InventoryReservedPayload;
import com.orderflow.order.messaging.event.PaymentCompletedPayload;
import com.orderflow.order.messaging.event.PaymentFailedPayload;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OrderStatusHistoryRepository;
import com.orderflow.order.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private ProductServiceClient productServiceClient;
    @Mock
    private IdempotencyKeyService idempotencyKeyService;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private OrderCreationWriter orderCreationWriter;

    @InjectMocks
    private OrderService orderService;

    private UUID userId;
    private UUID productId;
    private UUID correlationId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        correlationId = UUID.randomUUID();
    }

    private Order pendingOrder(UUID owner) {
        Order order = Order.create(owner);
        order.addItem(OrderItem.of(order, productId, "Widget", new BigDecimal("9.99"), 2));
        return order;
    }

    // ---- create ---------------------------------------------------------------------------

    @Test
    void create_withoutIdempotencyKey_resolvesProductAndPersists() {
        when(productServiceClient.getProduct(productId)).thenReturn(new ProductDto(productId, "Widget", new BigDecimal("9.99")));
        Order persisted = pendingOrder(userId);
        when(orderCreationWriter.persist(eq(userId), any(), eq(correlationId))).thenReturn(persisted);

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 2)));
        OrderService.CreateResult result = orderService.create(userId, request, null, correlationId);

        assertThat(result.alreadyExisted()).isFalse();
        assertThat(result.order().id()).isEqualTo(persisted.getId());
        verifyNoInteractions(idempotencyKeyService);
    }

    @Test
    void create_withIdempotencyKey_firstTime_completesReservation() {
        when(idempotencyKeyService.reserve("key-1")).thenReturn(new IdempotencyKeyService.Acquired());
        when(productServiceClient.getProduct(productId)).thenReturn(new ProductDto(productId, "Widget", new BigDecimal("9.99")));
        Order persisted = pendingOrder(userId);
        when(orderCreationWriter.persist(eq(userId), any(), eq(correlationId))).thenReturn(persisted);

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 2)));
        OrderService.CreateResult result = orderService.create(userId, request, "key-1", correlationId);

        assertThat(result.alreadyExisted()).isFalse();
        verify(idempotencyKeyService).complete("key-1", persisted.getId());
        verify(idempotencyKeyService, never()).release(anyString());
    }

    @Test
    void create_withIdempotencyKey_alreadyResolved_returnsExistingOrderWithoutCreating() {
        Order existing = pendingOrder(userId);
        when(idempotencyKeyService.reserve("key-1")).thenReturn(new IdempotencyKeyService.AlreadyResolved(existing.getId()));
        when(orderRepository.findByIdWithItems(existing.getId())).thenReturn(Optional.of(existing));

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 2)));
        OrderService.CreateResult result = orderService.create(userId, request, "key-1", correlationId);

        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.order().id()).isEqualTo(existing.getId());
        verifyNoInteractions(productServiceClient, orderCreationWriter);
    }

    @Test
    void create_withIdempotencyKey_creationFails_releasesReservation() {
        when(idempotencyKeyService.reserve("key-1")).thenReturn(new IdempotencyKeyService.Acquired());
        when(productServiceClient.getProduct(productId)).thenThrow(new RuntimeException("boom"));

        CreateOrderRequest request = new CreateOrderRequest(List.of(new OrderItemRequest(productId, 2)));

        assertThatThrownBy(() -> orderService.create(userId, request, "key-1", correlationId))
                .isInstanceOf(RuntimeException.class);
        verify(idempotencyKeyService).release("key-1");
        verify(idempotencyKeyService, never()).complete(anyString(), any());
    }

    // ---- getById / ownership ----------------------------------------------------------------

    @Test
    void getById_owner_succeeds() {
        Order order = pendingOrder(userId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        var response = orderService.getById(order.getId(), userId, false);

        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void getById_nonOwnerNonAdmin_throwsAccessDenied() {
        Order order = pendingOrder(userId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getById(order.getId(), UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getById_nonOwnerAdmin_succeeds() {
        Order order = pendingOrder(userId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        var response = orderService.getById(order.getId(), UUID.randomUUID(), true);

        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void getById_notFound_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(id, userId, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listForUser_mapsPageToResponses() {
        Order order = pendingOrder(userId);
        Page<Order> page = new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1);
        when(orderRepository.findAllByUserId(eq(userId), any())).thenReturn(page);

        Page<?> result = orderService.listForUser(userId, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ---- saga event handlers ----------------------------------------------------------------

    private EventEnvelope<InventoryReservedPayload> inventoryReservedEnvelope(UUID orderId) {
        return new EventEnvelope<>(UUID.randomUUID(), "InventoryReserved", correlationId, Instant.now(),
                new InventoryReservedPayload(orderId, UUID.randomUUID(), List.of()));
    }

    @Test
    void applyInventoryReserved_firstDelivery_transitionsToAwaitingPayment() {
        Order order = pendingOrder(userId);
        var envelope = inventoryReservedEnvelope(order.getId());
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyInventoryReserved(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        verify(orderStatusHistoryRepository).save(any());
    }

    @Test
    void applyInventoryReserved_duplicateDelivery_isNoOp() {
        var envelope = inventoryReservedEnvelope(UUID.randomUUID());
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(0);

        orderService.applyInventoryReserved(envelope);

        verifyNoInteractions(orderRepository, orderStatusHistoryRepository);
    }

    @Test
    void applyInventoryReserved_orderInWrongState_isNoOp() {
        Order order = pendingOrder(userId);
        order.setStatus(OrderStatus.CONFIRMED);
        var envelope = inventoryReservedEnvelope(order.getId());
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyInventoryReserved(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderStatusHistoryRepository, never()).save(any());
    }

    @Test
    void applyInventoryReserved_unknownOrder_isNoOp() {
        var envelope = inventoryReservedEnvelope(UUID.randomUUID());
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(any())).thenReturn(Optional.empty());

        orderService.applyInventoryReserved(envelope);

        verify(orderStatusHistoryRepository, never()).save(any());
    }

    @Test
    void applyInventoryReservationFailed_transitionsToFailedAndWritesOutbox() {
        Order order = pendingOrder(userId);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "InventoryReservationFailed", correlationId, Instant.now(),
                new InventoryReservationFailedPayload(order.getId(), "Out of stock", List.of()));
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyInventoryReservationFailed(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(outboxWriter).write(eq(order.getId()), eq("OrderFailed"), eq("order.failed"), any(), eq(correlationId));
    }

    @Test
    void applyPaymentCompleted_transitionsToConfirmedAndWritesOutbox() {
        Order order = pendingOrder(userId);
        order.setStatus(OrderStatus.AWAITING_PAYMENT);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentCompleted", correlationId, Instant.now(),
                new PaymentCompletedPayload(order.getId(), UUID.randomUUID(), new BigDecimal("19.98"), "txn-1"));
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyPaymentCompleted(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outboxWriter).write(eq(order.getId()), eq("OrderConfirmed"), eq("order.confirmed"), any(), eq(correlationId));
    }

    @Test
    void applyPaymentCompleted_orderStillPending_isNoOp() {
        Order order = pendingOrder(userId);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentCompleted", correlationId, Instant.now(),
                new PaymentCompletedPayload(order.getId(), UUID.randomUUID(), new BigDecimal("19.98"), "txn-1"));
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyPaymentCompleted(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void applyPaymentFailed_transitionsToFailedAndWritesOutbox() {
        Order order = pendingOrder(userId);
        order.setStatus(OrderStatus.AWAITING_PAYMENT);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "PaymentFailed", correlationId, Instant.now(),
                new PaymentFailedPayload(order.getId(), "Card declined"));
        when(processedEventRepository.insertIfAbsent(any(), eq(envelope.eventId()))).thenReturn(1);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderService.applyPaymentFailed(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(outboxWriter, times(1)).write(eq(order.getId()), eq("OrderFailed"), eq("order.failed"), any(), eq(correlationId));
    }
}
