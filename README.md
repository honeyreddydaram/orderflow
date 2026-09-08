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
[Order Service](#order-service) below. Application services are added incrementally per the
milestones in the architecture doc.

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
