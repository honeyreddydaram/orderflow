# OrderFlow

A production-style, event-driven e-commerce platform built as a portfolio project: six Spring Boot
microservices (Auth, Product, Order, Inventory, Payment, Notification) coordinating an order saga
over Kafka, with Postgres per service, Redis caching, JWT auth, and a React/TypeScript frontend.

See [`docs/architecture.md`](docs/architecture.md) for the full architecture, event flows,
concurrency/idempotency/retry strategy, and implementation milestones.

## Status

Milestone 1 (scaffolding) complete. Milestone 2 (Auth Service) complete: registration/login, RS256
JWT issuance with rotating refresh tokens, BCrypt, Flyway-managed schema, unit tests, and a
Testcontainers-based integration test — verified in CI. Milestone 3 (Product Service) complete:
product CRUD, pagination, Redis-cached reads with scoped cache invalidation on writes — see
[Product Service](#product-service) below for API examples. Milestone 4 (Order Service) complete:
order creation with synchronous Product Service validation, transactional outbox, and full saga
participation (produces `order.created`/`order.confirmed`/`order.failed`, consumes
`inventory.reserved`/`inventory.reservation-failed`/`payment.completed`/`payment.failed`) built
against the event schemas Inventory/Payment Service will implement next — see
[Order Service](#order-service) below. Milestone 5 (Inventory Service) complete: stock model with
manual optimistic-locking (conditional bulk `UPDATE`, not JPA `@Version`), consumes `order.created`
for all-or-nothing multi-item reservation, publishes `inventory.reserved`/`inventory.reservation-failed`,
and consumes `payment.completed`/`payment.failed` to permanently decrement or release reserved stock
— see [Inventory Service](#inventory-service) below. Milestone 6 (Payment Service) complete: consumes
`inventory.reserved`, decides approve/decline with a deterministic amount-threshold rule, publishes
`payment.completed`/`payment.failed` — see [Payment Service](#payment-service) below. Milestone 7
(Notification Service) complete: consumes `order.confirmed`/`order.failed` and simulates sending a
notification — the last saga participant, and the first service with no transactional outbox, since
it publishes nothing downstream — see [Notification Service](#notification-service) below.
Application services are added incrementally per the milestones in the architecture doc.

**Known issue:** on this project's primary dev machine (Windows + Docker Desktop), the
Testcontainers-based integration tests cannot run reliably locally — see
[`docs/architecture.md` section 21](docs/architecture.md#21-known-local-environment-limitation-testcontainers-on-windows--docker-desktop)
for the root-cause investigation. They are verified via CI instead. All other tests run locally
without issue.

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker + Docker Compose (for local infra and Testcontainers-based integration tests)
- Node.js 20+ (for the frontend, added later)

## Local infrastructure

```
docker compose up -d
```

Brings up Postgres (with one database per service), Kafka (KRaft mode, no ZooKeeper), Redis, and
Kafka UI at http://localhost:8090.

## Build

```
mvn -B verify
```

## Product Service

Runs on port 8082. Reads (`GET`) are public; writes require a bearer access token issued by Auth
Service with the `ADMIN` role. Product Service only ever *verifies* tokens (loads Auth's RSA
public key) — it never holds a private key or issues tokens itself.

List products (paginated, cached):
```
curl "http://localhost:8082/api/v1/products?page=0&size=20&sort=price,asc"
```

Get one product:
```
curl "http://localhost:8082/api/v1/products/{id}"
```

Create a product (ADMIN token required):
```
curl -X POST http://localhost:8082/api/v1/products \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Widget", "description": "A widget", "price": 9.99, "stockQuantity": 100}'
```

Update a product (evicts its cache entry and the product-list cache):
```
curl -X PUT http://localhost:8082/api/v1/products/{id} \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Widget Pro", "description": "Better widget", "price": 14.99, "stockQuantity": 50}'
```

Delete a product (soft delete — sets `active=false`, evicts the same cache entries):
```
curl -X DELETE http://localhost:8082/api/v1/products/{id} \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

## Order Service

Runs on port 8083. Every endpoint requires authentication — unlike Product Service, there's no
public read surface, since an order always belongs to someone. Order Service only ever *verifies*
tokens, the same public-key-only model as Product Service.

Create an order (validates each product against Product Service, snapshotting name/price; publishes
`OrderCreated` via the transactional outbox):
```
curl -X POST http://localhost:8083/api/v1/orders \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"items": [{"productId": "<product-uuid>", "quantity": 2}]}'
```

The `Idempotency-Key` header is optional but recommended for anything that might be retried (e.g. a
double-tapped "place order" button) — replaying the same key returns the original order (`200`)
instead of creating a duplicate (`201`), even under concurrent replay.

Get order detail (owner or ADMIN only):
```
curl http://localhost:8083/api/v1/orders/{id} -H "Authorization: Bearer $ACCESS_TOKEN"
```

Lightweight status polling:
```
curl http://localhost:8083/api/v1/orders/{id}/status -H "Authorization: Bearer $ACCESS_TOKEN"
```

Paginated order history for the caller:
```
curl "http://localhost:8083/api/v1/orders?page=0&size=20" -H "Authorization: Bearer $ACCESS_TOKEN"
```

An order progresses `PENDING → AWAITING_PAYMENT → CONFIRMED` (or `FAILED` if inventory reservation
or payment fails) as Order Service consumes events from Inventory/Payment Service on Kafka — watch
it happen live via Kafka UI at http://localhost:8090 once those services exist. Until then, the
consumer side can be exercised directly by publishing a matching event onto `inventory.reserved` or
`payment.completed` (see `OrderControllerIntegrationTest` for the exact message shape).

## Inventory Service

Runs on port 8084. Every endpoint requires an `ADMIN` bearer token — unlike Product Service, there's
no public read surface, since stock levels are an internal/operational concern, not customer-facing.
Inventory Service only ever *verifies* tokens, the same public-key-only model as the other services.
It has no Redis dependency: its admin endpoints have no customer-facing retry/idempotency-key use
case, unlike order placement.

Create/set stock for a product:
```
curl -X POST http://localhost:8084/api/v1/inventory \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId": "<product-uuid>", "availableQty": 100}'
```

Get current stock:
```
curl http://localhost:8084/api/v1/inventory/{productId} -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN"
```

Manually adjust stock (positive or negative delta; rejected if it would drive `availableQty` below
zero):
```
curl -X PUT http://localhost:8084/api/v1/inventory/{productId}/adjust \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"adjustment": -10}'
```

On `order.created`, Inventory Service attempts an all-or-nothing reservation across every item in
the order (optimistic locking with bounded retry on version conflicts — see
[`docs/architecture.md` section 9](docs/architecture.md#9-inventory-concurrency-strategy) for the
exact conditional-update SQL and the crash-safety argument for why the idempotency marker and the
reservation outcome are always committed in the same transaction). Success publishes
`inventory.reserved`; if any item is short, every provisional reservation in the batch is rolled
back and `inventory.reservation-failed` is published instead. `payment.completed` converts a
reservation's `reserved_qty` into a permanent decrement; `payment.failed` releases it back to
`available_qty`.

## Payment Service

Runs on port 8085. Almost entirely event-driven — its only REST endpoint is a read. Every endpoint
requires authentication; like Order Service (and unlike Inventory Service's blanket ADMIN-only
model), ownership is enforced in the service layer rather than at the URL level, since a payment
always belongs to whoever placed the order. Payment Service only ever *verifies* tokens, the same
public-key-only model as the other services. It has no Redis dependency and no stock-style
concurrency machinery: the only write path is the Kafka consumer, and a payment decision is made
once and never mutated afterward.

Get the payment record for an order (owner or ADMIN):
```
curl http://localhost:8085/api/v1/payments/{orderId} -H "Authorization: Bearer $ACCESS_TOKEN"
```

On `inventory.reserved`, Payment Service decides approve/decline with a deterministic rule —
`totalAmount` over `orderflow.payment.decline-threshold` (default `10000.00`) declines with a
descriptive reason, otherwise it approves with a generated transaction reference — rather than
randomly, so both the approve and decline paths are reproducible in tests. Approval publishes
`payment.completed`; decline publishes `payment.failed`, both already consumed by Order Service
(order confirmation/failure) and Inventory Service (permanent decrement/release).

## Notification Service

Runs on port 8086. The last saga participant, and the first service that publishes nothing
downstream — there's no transactional outbox here, since nothing consumes "a notification was
sent." Every endpoint requires authentication; the one REST endpoint is inherently self-scoped
(the caller's own notifications), so there's no separate ownership check the way Order/Payment
Service need. Notification Service only ever *verifies* tokens, the same public-key-only model as
the other services. No Redis, no concurrency machinery.

List the caller's own notifications, newest first:
```
curl "http://localhost:8086/api/v1/notifications?page=0&size=20" -H "Authorization: Bearer $ACCESS_TOKEN"
```

On `order.confirmed`/`order.failed`, Notification Service simulates sending a notification — no
real email/SMS provider integration in v1 — by persisting a `notifications` row and logging it via
a concrete `NotificationSender` component. Duplicate deliveries are prevented the same way as every
other consumer in the system: a `processed_events` marker committed in the same transaction as the
notification row, so a crash between "marked processed" and "notification recorded" can never
happen (see `docs/architecture.md` section 10/12).
