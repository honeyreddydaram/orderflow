# OrderFlow

A production-style, event-driven e-commerce platform built as a portfolio project: six Spring Boot
microservices (Auth, Product, Order, Inventory, Payment, Notification) coordinating an order saga
over Kafka, with Postgres per service, Redis caching, JWT auth, and a React/TypeScript frontend.

See [`docs/architecture.md`](docs/architecture.md) for the full architecture, event flows,
concurrency/idempotency/retry strategy, and implementation milestones.

## Status

Milestone 1 (scaffolding) complete. Milestone 2 (Auth Service) implemented: registration/login,
RS256 JWT issuance with rotating refresh tokens, BCrypt, Flyway-managed schema, unit tests, and a
Testcontainers-based integration test. Application services are added incrementally per the
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
