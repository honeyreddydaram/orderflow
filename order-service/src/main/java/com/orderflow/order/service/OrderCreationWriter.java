package com.orderflow.order.service;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.domain.OrderStatusHistory;
import com.orderflow.order.messaging.event.OrderCreatedPayload;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.repository.OrderStatusHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Separate bean (not a private method on OrderService) so {@code @Transactional} is honored via
 * the Spring AOP proxy - calling a @Transactional method on `this` from within the same class
 * bypasses the proxy entirely and silently runs with no transaction. This bean does DB writes
 * only; the Product Service HTTP call happens in OrderService before this is invoked, so no DB
 * connection is ever held open across a network call.
 */
@Component
public class OrderCreationWriter {

    public record ResolvedItem(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
    }

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OutboxWriter outboxWriter;

    public OrderCreationWriter(OrderRepository orderRepository,
                                OrderStatusHistoryRepository orderStatusHistoryRepository,
                                OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public Order persist(UUID userId, List<ResolvedItem> resolvedItems, UUID correlationId) {
        Order order = Order.create(userId);
        for (ResolvedItem item : resolvedItems) {
            order.addItem(OrderItem.of(order, item.productId(), item.productName(), item.unitPrice(), item.quantity()));
        }
        orderRepository.save(order);
        orderStatusHistoryRepository.save(OrderStatusHistory.record(order.getId(), null, OrderStatus.PENDING, "Order created"));

        List<OrderCreatedPayload.Item> payloadItems = order.getItems().stream()
                .map(i -> new OrderCreatedPayload.Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        outboxWriter.write(order.getId(), "OrderCreated", "order.created",
                new OrderCreatedPayload(order.getId(), userId, payloadItems, order.getTotalAmount()), correlationId);

        return order;
    }
}
