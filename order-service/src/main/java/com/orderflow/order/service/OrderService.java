package com.orderflow.order.service;

import com.orderflow.common.event.EventEnvelope;
import com.orderflow.common.error.ResourceNotFoundException;
import com.orderflow.order.client.ProductDto;
import com.orderflow.order.client.ProductServiceClient;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.domain.OrderStatusHistory;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderItemRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.messaging.event.InventoryReservationFailedPayload;
import com.orderflow.order.messaging.event.InventoryReservedPayload;
import com.orderflow.order.messaging.event.OrderConfirmedPayload;
import com.orderflow.order.messaging.event.OrderFailedPayload;
import com.orderflow.order.messaging.event.PaymentCompletedPayload;
import com.orderflow.order.messaging.event.PaymentFailedPayload;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OrderStatusHistoryRepository;
import com.orderflow.order.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ProductServiceClient productServiceClient;
    private final IdempotencyKeyService idempotencyKeyService;
    private final OutboxWriter outboxWriter;
    private final OrderCreationWriter orderCreationWriter;

    public OrderService(OrderRepository orderRepository,
                         OrderStatusHistoryRepository orderStatusHistoryRepository,
                         ProcessedEventRepository processedEventRepository,
                         ProductServiceClient productServiceClient,
                         IdempotencyKeyService idempotencyKeyService,
                         OutboxWriter outboxWriter,
                         OrderCreationWriter orderCreationWriter) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.processedEventRepository = processedEventRepository;
        this.productServiceClient = productServiceClient;
        this.idempotencyKeyService = idempotencyKeyService;
        this.outboxWriter = outboxWriter;
        this.orderCreationWriter = orderCreationWriter;
    }

    public record CreateResult(OrderResponse order, boolean alreadyExisted) {
    }

    public CreateResult create(UUID userId, CreateOrderRequest request, String idempotencyKey, UUID correlationId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new CreateResult(OrderResponse.from(resolveAndPersist(userId, request, correlationId)), false);
        }

        var reservation = idempotencyKeyService.reserve(idempotencyKey);
        if (reservation instanceof IdempotencyKeyService.AlreadyResolved resolved) {
            Order existing = orderRepository.findById(resolved.orderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order " + resolved.orderId() + " not found"));
            return new CreateResult(OrderResponse.from(existing), true);
        }

        try {
            Order order = resolveAndPersist(userId, request, correlationId);
            idempotencyKeyService.complete(idempotencyKey, order.getId());
            return new CreateResult(OrderResponse.from(order), false);
        } catch (RuntimeException e) {
            idempotencyKeyService.release(idempotencyKey);
            throw e;
        }
    }

    /**
     * Resolves every referenced product via a synchronous HTTP call BEFORE opening any DB
     * transaction (an HTTP call must never hold a DB connection open), then delegates the actual
     * persistence to a separate bean so its {@code @Transactional} annotation is honored - see
     * OrderCreationWriter's javadoc for why this can't just be a private method here.
     */
    private Order resolveAndPersist(UUID userId, CreateOrderRequest request, UUID correlationId) {
        List<OrderCreationWriter.ResolvedItem> resolvedItems = request.items().stream()
                .map(this::resolveItem)
                .toList();
        return orderCreationWriter.persist(userId, resolvedItems, correlationId);
    }

    private OrderCreationWriter.ResolvedItem resolveItem(OrderItemRequest itemRequest) {
        ProductDto product = productServiceClient.getProduct(itemRequest.productId());
        return new OrderCreationWriter.ResolvedItem(product.id(), product.name(), product.price(), itemRequest.quantity());
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID orderId, UUID requesterId, boolean isAdmin) {
        Order order = findOwnedOrThrow(orderId, requesterId, isAdmin);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listForUser(UUID userId, Pageable pageable) {
        return orderRepository.findAllByUserId(userId, pageable).map(OrderResponse::from);
    }

    private Order findOwnedOrThrow(UUID orderId, UUID requesterId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
        if (!isAdmin && !order.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("Order " + orderId + " does not belong to the requester");
        }
        return order;
    }

    // ---- Saga event handlers -------------------------------------------------------------

    @Transactional
    public void applyInventoryReserved(EventEnvelope<InventoryReservedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withOrderInExpectedState(envelope.payload().orderId(), OrderStatus.PENDING, "inventory.reserved", order -> {
            transition(order, OrderStatus.AWAITING_PAYMENT, null);
        });
    }

    @Transactional
    public void applyInventoryReservationFailed(EventEnvelope<InventoryReservationFailedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withOrderInExpectedState(envelope.payload().orderId(), OrderStatus.PENDING, "inventory.reservation-failed", order -> {
            transition(order, OrderStatus.FAILED, envelope.payload().reason());
            outboxWriter.write(order.getId(), "OrderFailed", "order.failed",
                    new OrderFailedPayload(order.getId(), order.getUserId(), envelope.payload().reason()),
                    envelope.correlationId());
        });
    }

    @Transactional
    public void applyPaymentCompleted(EventEnvelope<PaymentCompletedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withOrderInExpectedState(envelope.payload().orderId(), OrderStatus.AWAITING_PAYMENT, "payment.completed", order -> {
            transition(order, OrderStatus.CONFIRMED, null);
            outboxWriter.write(order.getId(), "OrderConfirmed", "order.confirmed",
                    new OrderConfirmedPayload(order.getId(), order.getUserId(), order.getTotalAmount()),
                    envelope.correlationId());
        });
    }

    @Transactional
    public void applyPaymentFailed(EventEnvelope<PaymentFailedPayload> envelope) {
        if (!firstDelivery(envelope.eventId())) {
            return;
        }
        withOrderInExpectedState(envelope.payload().orderId(), OrderStatus.AWAITING_PAYMENT, "payment.failed", order -> {
            transition(order, OrderStatus.FAILED, envelope.payload().reason());
            outboxWriter.write(order.getId(), "OrderFailed", "order.failed",
                    new OrderFailedPayload(order.getId(), order.getUserId(), envelope.payload().reason()),
                    envelope.correlationId());
        });
    }

    private boolean firstDelivery(UUID eventId) {
        int inserted = processedEventRepository.insertIfAbsent(UUID.randomUUID(), eventId);
        if (inserted == 0) {
            log.info("Duplicate delivery of event {} - already processed, skipping", eventId);
            return false;
        }
        return true;
    }

    private void withOrderInExpectedState(UUID orderId, OrderStatus expected, String eventName, java.util.function.Consumer<Order> action) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.error("Received {} for unknown order {} - ignoring", eventName, orderId);
            return;
        }
        if (order.getStatus() != expected) {
            log.warn("Received {} for order {} while in unexpected state {} (expected {}) - ignoring",
                    eventName, orderId, order.getStatus(), expected);
            return;
        }
        action.accept(order);
    }

    private void transition(Order order, OrderStatus newStatus, String reason) {
        OrderStatus previous = order.getStatus();
        order.setStatus(newStatus);
        orderStatusHistoryRepository.save(OrderStatusHistory.record(order.getId(), previous, newStatus, reason));
    }
}
