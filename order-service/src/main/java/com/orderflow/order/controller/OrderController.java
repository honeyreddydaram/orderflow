package com.orderflow.order.controller;

import com.orderflow.common.web.CorrelationIdFilter;
import com.orderflow.order.dto.CreateOrderRequest;
import com.orderflow.order.dto.OrderResponse;
import com.orderflow.order.dto.OrderStatusResponse;
import com.orderflow.order.service.OrderService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        OrderService.CreateResult result = orderService.create(userId, request, idempotencyKey, correlationId());
        HttpStatus status = result.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.order());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getById(@PathVariable UUID id,
                                                  @AuthenticationPrincipal UUID userId,
                                                  Authentication authentication) {
        return ResponseEntity.ok(orderService.getById(id, userId, isAdmin(authentication)));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<OrderStatusResponse> getStatus(@PathVariable UUID id,
                                                          @AuthenticationPrincipal UUID userId,
                                                          Authentication authentication) {
        OrderResponse order = orderService.getById(id, userId, isAdmin(authentication));
        return ResponseEntity.ok(new OrderStatusResponse(order.id(), order.status(), order.updatedAt()));
    }

    @GetMapping
    public ResponseEntity<Page<OrderResponse>> list(@AuthenticationPrincipal UUID userId,
                                                     @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(orderService.listForUser(userId, pageable));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    private static UUID correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Client supplied a non-UUID X-Correlation-Id - fall through to a generated one.
            }
        }
        return UUID.randomUUID();
    }
}
