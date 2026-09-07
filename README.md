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
[Product Service](#product-service) below for API examples. Application services are added
incrementally per the milestones in the architecture doc.

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
