# OrderFlow — Event-Driven E-Commerce Platform: Architecture

## Context

OrderFlow is a portfolio project: a production-style, event-driven microservices system realistic
enough to defend in an SDE interview, built with Java 21 / Spring Boot 3 / Kafka / Postgres / Redis
/ Docker / GitHub Actions. This document is the approved architecture and is kept up to date as the
system is built; see the Implementation Milestones section for build order and current status.

---

## 1. Overall Architecture

Six independently deployable Spring Boot services, each owning its own PostgreSQL schema (database-
per-service), communicating synchronously via REST only where a caller needs an immediate answer
(Auth login, Product browse, Order creation ack), and asynchronously via Kafka for everything that
drives the order saga forward. Redis is used for read-through caching (product catalog) and as a
distributed lock / idempotency-key store. There is no API Gateway in v1 (see §20) — the React
frontend calls each service directly on its own port in local dev, with a note on how a gateway would
slot in later.

```
                         ┌───────────────┐
                         │   React SPA   │
                         └───────┬───────┘
                    (REST + JWT bearer token)
        ┌──────────┬────────────┼────────────┬───────────┐
        ▼          ▼            ▼             ▼           ▼
   ┌────────┐ ┌─────────┐  ┌─────────┐  ┌───────────┐ ┌────────────┐
   │  Auth  │ │ Product │  │  Order  │  │ Inventory │ │  Payment   │
   │Service │ │ Service │  │ Service │  │  Service  │ │  Service   │
   └───┬────┘ └────┬────┘  └────┬────┘  └─────┬─────┘ └─────┬──────┘
       │           │            │             │             │
     pg:auth    pg:product   pg:order      pg:inventory   pg:payment
                     │            │             │             │
                     │            └──────┬──────┴──────┬──────┘
                     │                   ▼             ▼
                     │             ┌─────────────────────┐
                     │             │   Apache Kafka       │
                     │             │  (topics + DLTs)     │
                     │             └──────────┬───────────┘
                     │                        ▼
                     │              ┌───────────────────┐
                     │              │ Notification Svc   │
                     │              └───────────────────┘
                     ▼
                ┌─────────┐
                │  Redis  │  (product cache, idempotency keys, distributed locks)
                └─────────┘
```

Cross-cutting: every inbound HTTP request and every Kafka message carries a **correlation ID**
(`X-Correlation-Id` header / `correlationId` event field), propagated through MDC for structured
JSON logs, so a single order's journey can be grepped across all six services.

---

## 2. Responsibility of Every Microservice

| Service | Responsibility |
|---|---|
| **Auth** | User registration, password hashing (BCrypt), login, JWT issuance (access + refresh), role management (`CUSTOMER`, `ADMIN`). Source of truth for identity. |
| **Product** | Product catalog CRUD (admin-only writes), pagination, price/stock info. Publishes nothing; read-heavy, cached in Redis. (Category browsing was deferred - see Milestone 3 note below.) |
| **Order** | Order creation & lifecycle state machine (`PENDING → AWAITING_PAYMENT → CONFIRMED → FAILED/CANCELLED`), order history per user, saga **orchestrator** for the order flow (via event choreography, see §6/§9 note on style), owns the "current truth" of an order. |
| **Inventory** | Stock levels per product/SKU, reservation on `OrderCreated`, release on failure/compensation, decrement on payment success. Owns concurrency control for stock. |
| **Payment** | Simulated payment processing (approve/decline via configurable rule, e.g. random or amount-based), idempotent charge records, publishes success/failure events. No real payment gateway integration in v1. |
| **Notification** | Consumes terminal events (`OrderConfirmed`, `OrderFailed`) and simulates sending email/SMS (logs + stores a `notifications` table). No outbound REST calls to real providers. |

---

## 3. Database Ownership (database-per-service, all Postgres, separate schemas/DBs)

- **auth_db**: `users`, `roles`, `refresh_tokens`
- **product_db**: `products` (id, name, description, price, stock_quantity, active, created_at, updated_at)
- **order_db**: `orders`, `order_items`, `order_status_history`, `outbox_events` (transactional
  outbox), `processed_events` (consumer idempotency table)
- **inventory_db**: `stock_items` (product_id as PK, available_qty, reserved_qty, version - plus
  a DB-level `CHECK` constraint that both quantities stay >= 0, belt-and-suspenders beneath the
  application-level guards), `reservations`, `reservation_items`, `outbox_events`, `processed_events`
- **payment_db**: `payments` (order_id UNIQUE, user_id, amount, status, transaction_ref, decline_reason),
  `outbox_events`, `processed_events` - no `payment_attempts` (dropped from the original sketch: v1
  has no retry-of-a-declined-charge flow, a decline is terminal, so there's nothing an attempts-audit
  table would record beyond what `payments` itself already holds)
- **notification_db**: `notifications` (order_id, user_id, type, subject, message - no unique
  constraint, since nothing looks up a notification by orderId), `processed_events` - no
  `outbox_events`, unlike every other service (see section 12): this is the end of the saga, so
  there's nothing further to publish

No service reaches into another's schema. Product data needed by Order (name/price snapshot) is
copied into `order_items` at order-creation time (classic saga pattern: snapshot, don't join across
services).

---

## 4. REST API Endpoints

**Auth Service** (`/api/v1/auth`)
- `POST /register` — create user
- `POST /login` — returns access + refresh JWT
- `POST /refresh` — rotate access token
- `POST /logout` — revoke refresh token
- `GET /me` — current user profile (JWT required)

**Product Service** (`/api/v1/products`, port 8082) — implemented in Milestone 3
- `GET /products?page=&size=&sort=` — paginated catalog (public, Redis-cached, max page size 100)
- `GET /products/{id}` — product detail (public, cached)
- `POST /products` — create (ADMIN)
- `PUT /products/{id}` — update (ADMIN, evicts cache)
- `DELETE /products/{id}` — soft delete (ADMIN, evicts cache)

No `category` filter/field yet - deferred until a real need for it appears (e.g. when the frontend
or Order Service requires it); adding it later is a non-breaking additive change.

**Order Service** (`/api/v1/orders`, port 8083) — implemented in Milestone 4
- `POST /orders` — create order from cart items (any authenticated user, JWT; optional
  `Idempotency-Key` header - see section 10)
- `GET /orders/{id}` — order detail + status (owner or ADMIN)
- `GET /orders?page=&size=` — paginated order history for current user
- `GET /orders/{id}/status` — lightweight polling endpoint for frontend status updates

Unlike Product Service, every endpoint here requires authentication - there is no public read
surface, since an order always belongs to someone.

**Inventory Service** (`/api/v1/inventory`, port 8084) — implemented in Milestone 5, ADMIN-only,
no public reads at all (unlike Product Service)
- `GET /inventory/{productId}` — current stock
- `POST /inventory` — create/set stock for a product (409 if a row already exists)
- `PUT /inventory/{productId}/adjust` — manual stock adjustment (signed delta), rejected if it
  would drive available stock negative

No cross-service validation against Product Service on `POST /inventory` - a deliberate
decoupling, since this is an ops/seeding operation, not a customer-facing flow.

**Payment Service** (`/api/v1/payments`) — mostly event-driven, thin REST for visibility
- `GET /payments/{orderId}` — payment record/status for an order (owner or ADMIN)

**Notification Service** (`/api/v1/notifications`, port 8086) — implemented in Milestone 7
- `GET /notifications?page=&size=` — the caller's own notifications, paginated, newest first

No `?userId=` query param (the original sketch) - letting any authenticated caller pass an
arbitrary `userId` would be an IDOR hole. Self-scoped only, like Order Service's `GET /orders`; no
ADMIN override, since nothing in the spec requires one.

All services expose Spring Boot Actuator (`/actuator/health`, `/actuator/info`, `/actuator/metrics`)
unauthenticated on a separate management port where feasible.

---

## 5. Kafka Topics

| Topic | Producer | Consumers | Key |
|---|---|---|---|
| `order.created` | Order | Inventory | orderId |
| `inventory.reserved` | Inventory | Payment | orderId |
| `inventory.reservation-failed` | Inventory | Order | orderId |
| `payment.completed` | Payment | Order, Notification | orderId |
| `payment.failed` | Payment | Order, Inventory | orderId |
| `order.confirmed` | Order | Notification | orderId |
| `order.failed` | Order | Notification | orderId |

Dead-letter topics (mirror + `.DLT` suffix), one per consumer topic:
`order.created.DLT`, `inventory.reserved.DLT`, `inventory.reservation-failed.DLT`,
`payment.completed.DLT`, `payment.failed.DLT`, `order.confirmed.DLT`, `order.failed.DLT`.

Partitioning: all topics keyed by `orderId` so events for a given order are strictly ordered within
a partition — critical for correctness of the saga without extra coordination. Default 3 partitions
per topic locally.

**Provisioning (Milestone 4):** rather than relying solely on the broker's auto-create setting,
Order Service explicitly declares every topic and DLT it produces to or consumes from as a
`NewTopic` Spring bean (`KafkaTopicConfig`), with partitions/replication-factor overridable via
`orderflow.kafka.partitions`/`orderflow.kafka.replication-factor` (defaults: 3 partitions, 1
replica, matching the single-broker local/CI KRaft cluster - override the replication factor for a
real multi-broker deployment). `NewTopic` bean declarations are idempotent, so Inventory and
Payment Service can declare the same topics again once built without conflict. Broker auto-create
remains enabled as a fallback safety net, not the primary mechanism.

---

## 6. Event Schemas and Event Flow

Common envelope (all events share this shape, JSON via Spring Kafka `JsonSerializer` + a small
`EventEnvelope<T>` wrapper):

```json
{
  "eventId": "uuid",
  "eventType": "OrderCreated",
  "correlationId": "uuid",
  "occurredAt": "2026-09-07T10:15:30Z",
  "payload": { ... }
}
```

- **OrderCreated**: `orderId, userId, items[{productId, quantity, unitPrice}], totalAmount`
- **InventoryReserved**: `orderId, reservationId, userId, totalAmount, items[{productId, quantity}]` -
  `userId`/`totalAmount` were added in Milestone 6, once Payment Service (a consumer of this event)
  needed an amount to charge and a userId to enforce ownership on `GET /payments/{orderId}`, neither
  of which the event originally carried. Inventory Service already had both on hand from the
  `OrderCreated` event it consumed to produce this one, so populating them cost nothing there; Order
  Service's own copy of the schema (it also consumes this event, to advance order status) was updated
  to match even though it ignores both fields, since Jackson's default `FAIL_ON_UNKNOWN_PROPERTIES`
  would otherwise route the message straight to Order Service's DLT the moment the two schemas
  drifted.
- **InventoryReservationFailed**: `orderId, reason, items[{productId, requestedQty, availableQty}]`
- **PaymentCompleted**: `orderId, paymentId, amount, transactionRef`
- **PaymentFailed**: `orderId, reason`
- **OrderConfirmed**: `orderId, userId, totalAmount`
- **OrderFailed**: `orderId, userId, reason`

Style: this is **choreography**, not central orchestration — each service reacts to the previous
event and emits the next. This is the right call for v1: fewer moving parts than a saga
orchestrator/state machine service, and the 5-step flow is linear enough that choreography stays
readable. (Documented as a tradeoff in §19.)

---

## 7. Successful Order Lifecycle

1. User `POST /orders` → Order Service validates items against Product Service (sync REST call for
   current price/existence), persists order as `PENDING`, publishes `OrderCreated`.
2. Inventory Service consumes `OrderCreated`, attempts atomic reservation (optimistic locking, see
   §9). On success: persists `Reservation`, publishes `InventoryReserved`, order → `AWAITING_PAYMENT`.
3. Payment Service consumes `InventoryReserved`, runs simulated charge, persists `Payment`, publishes
   `PaymentCompleted`.
4. Order Service consumes `PaymentCompleted` → order → `CONFIRMED`, publishes `OrderConfirmed`.
5. Inventory Service consumes `PaymentCompleted` → converts `reserved_qty` to permanent decrement.
6. Notification Service consumes `OrderConfirmed` → simulates sending confirmation email, stores
   notification record.

Frontend polls `GET /orders/{id}/status` (or short-polls) to reflect state transitions.

---

## 8. Failed Order / Payment Lifecycle (Compensation)

**Case A — Inventory can't reserve (out of stock):**
Inventory publishes `InventoryReservationFailed` → Order Service consumes it, order → `FAILED`,
publishes `OrderFailed` → Notification sends failure notice. No compensation needed (nothing was
reserved).

**Case B — Payment fails after inventory reserved:**
Payment publishes `PaymentFailed` → consumed by **both**:
- Order Service: order → `FAILED`, publishes `OrderFailed`.
- Inventory Service: releases the reservation (`reserved_qty -= n`, `available_qty += n`), marks
  reservation `RELEASED`.

Notification consumes `OrderFailed` → sends failure notice.

This is the compensating-transaction pattern: no distributed transaction/2PC, just forward
progress + explicit undo events, which is the standard, interview-defensible answer for sagas.

---

## 9. Inventory Concurrency Strategy (prevent overselling)

Implemented in Milestone 5. `stock_items` has `available_qty`, `reserved_qty`, and a plain `version`
column - deliberately **not** JPA's automatic `@Version` mechanism, since the design calls for a
manual conditional bulk `UPDATE` combining the version check with a business-invariant check in one
atomic round trip, which JPA's own optimistic-lock machinery doesn't directly express. All four
stock mutations share this shape via `StockItemRepository`/`StockMutator`:

```sql
-- reserve (order.created)
UPDATE stock_items SET available_qty = available_qty - :qty, reserved_qty = reserved_qty + :qty,
  version = version + 1 WHERE product_id = :id AND available_qty >= :qty AND version = :version
-- decrement (payment.completed) and release (payment.failed) both also guard reserved_qty >= :qty,
-- so a malformed or duplicate mutation can never drive reserved stock negative:
UPDATE stock_items SET reserved_qty = reserved_qty - :qty, version = version + 1
  WHERE product_id = :id AND reserved_qty >= :qty AND version = :version
UPDATE stock_items SET available_qty = available_qty + :qty, reserved_qty = reserved_qty - :qty,
  version = version + 1 WHERE product_id = :id AND reserved_qty >= :qty AND version = :version
```

On 0 rows affected, `StockMutator` re-reads the row: if the invariant still holds, it was a version
conflict, and it retries with the fresh version (bounded at 3 attempts); if the invariant no longer
holds, it fails fast without retrying. A DB-level `CHECK (available_qty >= 0 AND reserved_qty >= 0)`
constraint on `stock_items` backs this up as a last-resort defense, beneath even these guards.

Reservation itself is all-or-nothing across every item in an order: `StockReservationWriter`
attempts each item within one transaction, and if any single item can't be reserved, it calls
`setRollbackOnly()` and returns a failure result rather than throwing - undoing every other item's
already-applied reservation for that same order, so a partially-reservable order never ends up
partially reserved. **Proven under real concurrent load** (`StockItemRepositoryTest.concurrentReservations_neverOversell`):
10 concurrent threads each requesting 1 unit against `available_qty = 5` results in exactly 5
successes, 5 failures, and `available_qty` at exactly 0 - never negative, never oversold.

This avoids pessimistic row locks (simpler, no long-held DB locks under Kafka consumer retries)
while still guaranteeing correctness under concurrent order bursts for the same product — the
classic flash-sale interview question, answered with optimistic concurrency + conditional update
  rather than `SELECT ... FOR UPDATE` (tradeoff discussed in §19).

---

## 10. Idempotency Strategy

Two layers, both implemented in Order Service (Milestone 4):

1. **Kafka consumer idempotency**: every consuming service has a `processed_events` table keyed on
   `eventId` (UUID from the envelope), unique-constrained. Rather than a plain `save()` (which
   throws `DataIntegrityViolationException` on a duplicate and can poison the surrounding
   transaction), the actual insert uses a native
   `INSERT ... ON CONFLICT (event_id) DO NOTHING` returning the affected-row count - 1 for a new
   event, 0 for a duplicate. This never throws, so a duplicate delivery is a plain no-op inside the
   same transaction as the business update, not an exception-driven rollback. This makes
   retries/redeliveries safe without relying on Kafka's own exactly-once semantics.
   **Critically, the marker insert must live INSIDE the same transaction as the business outcome it
   guards, never as a separate up-front step** - if it were committed before the business work and
   the process crashed in between, Kafka redelivery would see the event as already processed and
   skip the unfinished work entirely, permanently losing it. Inventory Service's `order.created`
   handling (Milestone 5) is the sharpest example: the marker insert, every item's reservation, and
   the `InventoryReserved` outbox row all commit together in one transaction; if any item can't be
   reserved, `setRollbackOnly()` rolls back that whole transaction *including the marker*, so the
   event is genuinely un-processed again, and a *separate* transaction (`InventoryReservationFailureWriter`)
   re-inserts the marker atomically with the `InventoryReservationFailed` outbox row instead. Proven
   by `InventoryEventListenerIntegrationTest.reservationSurvivesATransientFailure_beforeCommit_viaKafkaRetry`,
   which makes the last write in the reservation transaction throw once (simulating a crash right
   before commit) and confirms Kafka's automatic redelivery completes the reservation exactly once
   - not lost, not double-applied.
2. **REST idempotency** for `POST /orders`: client may send an `Idempotency-Key` header. A plain
   "check Redis, then create, then write Redis" has a TOCTOU race - two concurrent requests with
   the same key could both miss the check and both create an order. Instead, Order Service uses an
   atomic `SET key PENDING NX EX <ttl>` as a reservation: only one concurrent caller can ever win
   it. The winner creates the order and resolves the key to the real order id (or deletes the key
   on failure, so the client can retry); every other concurrent caller either gets the losing
   `NX` result and polls briefly for the winner's result, or - if it arrives after the winner
   finished - reads the resolved order id directly. Proven under real concurrent load in
   `OrderIdempotencyConcurrencyTest` (10 simultaneous requests, same key → exactly one order and
   one `OrderCreated` outbox row).

---

## 11. Retry and Dead-Letter Strategy

- Spring Kafka `DefaultErrorHandler` with `ExponentialBackOff` (e.g. 500ms base, x2 multiplier, max
  3–5 attempts) for transient failures (DB connection blips, optimistic-lock exhaustion).
- Non-retryable exceptions (bad payload / deserialization errors, business-rule violations that will
  never succeed on retry) are classified and sent straight to the DLT.
- After retries are exhausted, `DeadLetterPublishingRecoverer` routes the failed record to
  `<topic>.DLT` with exception metadata in headers (exception class, message, stack trace excerpt,
  original topic/partition/offset).
- DLT topics are monitored only (no automatic reprocessing in v1); a small admin endpoint or doc note
  describes manual replay via a Kafka console consumer/producer — automatic DLT reprocessing is
  called out as a v2 improvement (§20).

---

## 12. Transaction Consistency Strategy

- No distributed transactions / 2PC. Consistency is **eventual**, achieved via the choreographed
  saga (§6–§8) plus compensation.
- Each service's local write (DB update) and event publish are kept consistent using the
  **transactional outbox pattern**: within one local DB transaction, the business table update and
  an `outbox_events` row are written together; a separate polling publisher (or Debezium-style
  approach — but for v1, a simple `@Scheduled` poller is enough) reads unpublished outbox rows and
  sends them to Kafka, then marks them sent. This avoids the classic "DB commit succeeded but Kafka
  publish failed" dual-write bug without needing CDC infrastructure.
- Order status is the single source of truth for "what happened"; consumers never assume success
  until they observe the confirming event.
- **Exception - Notification Service has no outbox at all.** It's the end of the saga: nothing
  consumes "a notification was sent," so there's no `OutboxEvent` entity, `OutboxWriter`, or
  `OutboxPoller`. Its `processed_events` marker still commits in the same transaction as the
  `Notification` row write, for the same crash-safety reason as everywhere else - the pattern that
  changes is what gets committed alongside the marker, not whether the marker's transaction boundary
  matters.

**Two Hibernate/Postgres gotchas hit while implementing this (Order Service) that will recur in
Inventory/Payment Service unless watched for:**
- `@Lob` on a `String` field maps to Postgres's `oid` large-object type by default, not `text` -
  if the Flyway migration defines a plain `TEXT` column (as `outbox_events.payload` does), schema
  validation fails at startup. A `TEXT` column has no practical size limit, so `@Lob` is unnecessary
  for JSON payload columns like this - just omit it.
- A method that intentionally has **no** `@Transactional` (e.g. because it makes an HTTP call
  before touching the database, like order creation validating against Product Service) must not
  return an entity with lazy-loaded collections and then read them later - the entity is detached
  the moment the repository call returns, and touching a lazy collection throws
  `LazyInitializationException`. Use a `JOIN FETCH` query (see `OrderRepository.findByIdWithItems`)
  to eagerly load what the caller needs in that one query, rather than relying on an open session.

**Two more gotchas hit writing Inventory Service's Testcontainers tests (both invisible on this
machine until CI actually ran them - see §21):**
- A repository's `@Modifying @Query` method throws `InvalidDataAccessApiUsageException` /
  `TransactionRequiredException` if invoked with no active transaction. This is silently fine in
  production (every caller is already inside a `@Transactional` service method) and in
  `@DataJpaTest` tests (which wrap each test in its own transaction by default) - but a test that
  deliberately suspends that ambient transaction to exercise real concurrency
  (`@Transactional(propagation = NOT_SUPPORTED)`, see `StockItemRepositoryTest.concurrentReservations_neverOversell`),
  or a `@SpringBootTest` class with no per-test transaction at all, must wrap any direct call to a
  `@Modifying` method in its own `TransactionTemplate.execute(...)`.
- Mockito's `doCallRealMethod()` cannot be used on a Spring Data JPA repository method - the
  interface method is formally `abstract` (it has no default body), and Mockito refuses to "call
  the real method" on an abstract method regardless of the fact that the runtime instance is a
  working dynamic proxy. Don't `@SpyBean` a repository interface to inject a fault; spy a concrete
  service-layer class instead (`InventoryEventListenerIntegrationTest` moved its fault injection
  from `ReservationRepository.save` to the concrete `OutboxWriter.write`).

---

## 13. Security Architecture

- Auth Service issues **JWT access tokens** (short-lived, e.g. 15 min, HS256 or RS256 — RS256
  preferred so other services can verify with a public key without calling Auth) and **refresh
  tokens** (longer-lived, stored hashed in `refresh_tokens`, rotated on use).
- All other services are **stateless resource servers**: Spring Security filter validates the JWT
  signature/expiry locally (no network call to Auth per request), extracts `userId` + `roles` into
  the security context.
- Role-based authorization: URL-based rules in each service's `SecurityFilterChain`
  (`.requestMatchers(HttpMethod.X, "...").hasRole("ADMIN")`) for whole-endpoint ADMIN-only rules
  (e.g. Product Service's writes) — simpler than `@PreAuthorize` method security for this coarse a
  grain, and used consistently as each service is built. Ownership checks (e.g. "this order belongs
  to the requesting user") are finer-grained and belong in the service layer instead.
- Passwords hashed with BCrypt; no plaintext ever logged.
- Correlation ID + userId (not the token) included in structured logs — never log tokens or
  passwords.
- CORS configured for the local React dev origin.
- **Gotcha every new service's `SecurityConfig` must include**: add
  `.requestMatchers("/error").permitAll()` as the first authorization rule. A `sendError()` from
  Spring Security (401 via the entry point, 403 via access-denied) makes the embedded servlet
  container perform a real internal forward to `/error`, which re-enters the *same* security filter
  chain as a second dispatch. If `/error` isn't explicitly permitted, that forward falls through to
  `anyRequest().authenticated()`, fails differently, and silently overwrites the original status code
  the client actually receives (discovered via Product Service's `create_rejectsCustomerRole_withForbidden`
  test: expected 403, observed 401 — only reproduces with a real embedded server, e.g.
  `@SpringBootTest(webEnvironment = RANDOM_PORT)`, not `@WebMvcTest`'s simulated dispatch, which
  doesn't perform a real container-level forward).

---

## 14. Redis Caching Strategy

- **Product catalog cache** (implemented, Milestone 3): two Spring Cache namespaces backed by
  Redis, `products` (single product by id) and `productList` (paginated listings), 10-minute TTL,
  JSON-serialized via `GenericJackson2JsonRedisSerializer`. Any create/update/delete evicts the
  affected product's entry (`products::{id}`) and the *entire* `productList` namespace (a single
  write can change page membership/counts across many pages, so partial list invalidation isn't
  safe) — and evicts nothing outside those two namespaces. Write-through invalidation, not pure TTL.
- **Idempotency-key store** for `POST /orders` (§10).
- **Optional**: JWT blacklist for logout/revocation (store revoked refresh-token IDs with TTL =
  remaining token life) — simple and avoids a DB round trip on every request.
- Redis is a performance/idempotency aid, never the system of record — Postgres always wins on
  conflict.

---

## 15. Testing Strategy

- **Unit tests (JUnit 5 + Mockito)**: service-layer business logic per microservice — order state
  transitions, inventory reservation math, JWT generation/validation, DTO validation edge cases.
  Target meaningful coverage of branching logic, not a coverage-percentage vanity metric.
- **Integration tests (Testcontainers)**: per service, spin up real Postgres (and Kafka + Redis
  where relevant) containers; test the actual repository queries, the optimistic-locking reservation
  UPDATE under simulated concurrency (parallel threads hammering one SKU), and Kafka
  produce/consume round-trips with an embedded/Testcontainers Kafka broker.
- **End-to-end saga test**: a dedicated test module (or the Order Service's IT suite) that boots
  Order + Inventory + Payment + Kafka + Postgres via Testcontainers/Docker Compose and asserts the
  full happy-path and failure-path event chains land in the right terminal DB states.
- **Contract sanity**: JSON schema/POJO round-trip tests for event envelopes so producer/consumer
  drift is caught early.
- CI (GitHub Actions) runs unit + Testcontainers integration tests on every PR using
  `mvn verify` per service (matrix build), with Docker-in-Docker available for Testcontainers.

---

## 16. Docker / Local Development Architecture

Single root `docker-compose.yml` for local dev:
- `postgres` (one container, multiple databases via init script) or one container per service DB —
  **recommendation: one Postgres container, multiple logical databases**, simplest for local dev
  while still respecting schema-per-service ownership at the application level.
- `kafka` + `zookeeper` (or KRaft mode, no ZK, since Java 21/2026-era Kafka supports KRaft cleanly —
  **recommendation: KRaft**, one less moving part).
- `redis`
- one container per Spring Boot service, each with its own `Dockerfile` (multi-stage: Maven build
  stage → slim JRE 21 runtime layer).
- `kafka-ui` (or `redpanda console`) for local topic/message inspection — nice interview demo value.
- Each service reads config via Spring profiles (`local`, `docker`, `test`) and environment
  variables for connection strings/secrets (no secrets committed).
- `docker-compose up` should bring up the entire platform for local demo purposes.

---

## 17. Proposed Repository / Directory Structure

Monorepo, multi-module feel but each service independently buildable:

```
orderflow/
├── docker-compose.yml
├── docker/
│   └── postgres-init/            # per-service DB creation scripts
├── .github/
│   └── workflows/
│       ├── ci.yml                # build+test matrix across services
│       └── docker-publish.yml    # (later) image build/push
├── common/                        # shared library: event envelope, correlation-id filter, error DTOs
│   └── (Maven module, published to local repo or path-referenced)
├── auth-service/
│   └── src/main/java/.../{controller,service,repository,domain,dto,config,security,exception}
├── product-service/
├── order-service/
├── inventory-service/
├── payment-service/
├── notification-service/
├── frontend/                      # React + TS app (later phase)
└── docs/
    ├── architecture.md            # this document, refined
    └── postman/ or *.http files
```

`common` module holds: `EventEnvelope<T>`, correlation-id `HandlerInterceptor`/Kafka
`ProducerInterceptor`, shared exception-response DTO, and JWT verification utilities used by
resource-server services — kept intentionally small to avoid it becoming a dumping ground.

---

## 18. Implementation Milestones (Order of Work)

1. **Scaffolding** — done: repo structure, root `docker-compose.yml` with Postgres/Kafka(KRaft)/Redis,
   `common` module, GitHub Actions CI.
2. **Auth Service** — done: registration/login/JWT issuance + refresh, unit + Testcontainers tests,
   verified in CI (23/23 tests passing at the time). (Nothing else depends on runtime Auth calls
   since verification is stateless, but it unlocks realistic frontend/API testing.)
3. **Product Service** — done: CRUD + pagination + Redis caching (`@Cacheable`/`@CacheEvict` over
   two namespaces, `products` and `productList`), soft delete, public reads/ADMIN writes via the
   same JWT model as Auth Service (public-key-only verification, no signing capability in this
   service), unit + `@WebMvcTest` + `@DataJpaTest` + Testcontainers (Postgres + Redis) tests,
   verified in CI (49/49 reactor-wide tests passing at the time). Independent of everything else
   besides Auth's JWT contract.
4. **Order Service** — done, **reordered ahead of Inventory Service** (originally planned as
   Milestone 5, after Inventory): order creation with synchronous Product Service validation
   (price/existence snapshot into `order_items`), transactional outbox (first use in the project -
   `outbox_events` + `OutboxPoller`), and the full saga consumer surface built against the
   schemas already fixed in section 6 - `inventory.reserved`, `inventory.reservation-failed`,
   `payment.completed`, `payment.failed` - even though Inventory/Payment Service don't exist yet.
   This means idempotency (`processed_events`, conflict-safe upsert), retry/DLT, and Kafka topic
   provisioning (§5/§11) were all built here on the first real Kafka consumer in the codebase,
   rather than deferred to a later "Kafka wiring" milestone as originally planned. Concurrent
   Idempotency-Key safety proven under real concurrent load (`OrderIdempotencyConcurrencyTest`);
   duplicate Kafka delivery proven to transition exactly once (`OrderControllerIntegrationTest`).
   Consequence of the reorder: no synchronous stock check at order-creation time yet - that arrives
   when Inventory Service is built and starts consuming `order.created`. Verified in CI (87/87
   reactor-wide tests passing at the time, 38 in order-service alone).
5. **Inventory Service** — done: stock model with manual optimistic-locking (conditional bulk
   `UPDATE`, not JPA `@Version`), consumes `order.created` (all-or-nothing multi-item reservation),
   publishes `inventory.reserved`/`inventory.reservation-failed` against the schemas Order Service
   already committed to; consumes `payment.completed` (permanent decrement) and `payment.failed`
   (release/compensation) even though Payment Service doesn't exist yet, same pattern as Order
   Service building against Inventory/Payment's contracts before they existed. No Redis - this
   service's admin endpoints have no customer-facing retry/idempotency-key use case. Unit,
   `@WebMvcTest`, `@DataJpaTest` + Testcontainers (incl. the overselling-prevention concurrency
   test), and Testcontainers (Postgres+Kafka) integration tests including a dedicated
   crash-before-commit/Kafka-redelivery test. Verified in CI (134/134 reactor-wide tests passing at
   the time, 47 in inventory-service alone).
6. **Payment Service** — done: consumes `inventory.reserved`, decides approve/decline with a
   deterministic amount-threshold rule (`orderflow.payment.decline-threshold`, not random - keeps
   both outcomes reproducible in tests), publishes `payment.completed`/`payment.failed` against the
   schemas Order and Inventory Service already consume. Single-phase transaction (marker + Payment
   row + outbox write together) since the decision is made before any write and always yields one
   outcome - simpler than Inventory Service's two-phase reservation writer. No Redis, no
   concurrency/version-invariant machinery (a payment is decided once and never mutated after).
   Required extending `InventoryReserved` with `userId`/`totalAmount` (see section 6) - a design gap
   caught before Payment Service needed to consume the event, fixed as its own isolated, separately
   CI-verified commit. Unit, `@WebMvcTest`, `@DataJpaTest` + Testcontainers, and Testcontainers
   (Postgres+Kafka) integration tests including a crash-before-commit/Kafka-redelivery test, built
   correctly on the first attempt using the two Testcontainers-test gotchas paid for in Milestone 5
   (see section 12). Verified in CI (154/154 reactor-wide tests passing at the time, 20 in
   payment-service alone).
7. **Notification Service** — done: consumes `order.confirmed`/`order.failed` and simulates sending
   a notification (a concrete `NotificationSender` logs it - no real email/SMS provider). The last
   saga participant, and the first service with no transactional outbox at all - it produces nothing
   downstream, so there's nothing to publish (see section 12). `processed_events` idempotency and
   Kafka retry/DLT are otherwise identical to every other consumer. REST surface is a single
   self-scoped, paginated `GET /api/v1/notifications` (see section 4) rather than the originally-
   sketched `?userId=` param, which would have been an IDOR hole. Unit, `@WebMvcTest`,
   `@DataJpaTest` + Testcontainers, and Testcontainers (Postgres+Kafka) integration tests including
   a crash-before-commit/Kafka-redelivery test targeting the concrete `NotificationSender.send` -
   built correctly on the first attempt, CI green with zero fix-up commits, applying the Milestone
   5/6 lessons (section 12) from the start rather than rediscovering them. Verified in CI (167/167
   reactor-wide tests passing at the time, 13 in notification-service alone).
8. **Cross-cutting polish**: any remaining gaps in correlation IDs, structured JSON logging, global
   exception handlers, Actuator - across whichever services need it once 5-7 are built.
9. **Concurrency/chaos testing**: parallel-order load test against one low-stock SKU to prove no
   overselling; kill-a-consumer-mid-processing test to prove idempotent redelivery end-to-end
   across real services (Order Service's own redelivery safety is already proven per-service).
13. **CI maturity**: full GitHub Actions matrix (build, unit, Testcontainers integration) on PR;
    Docker image build job.
14. **Frontend (React + TS)**: login/register, product browsing, cart, order placement, order status
    view polling — last, once the backend contract is stable.
15. **README + architecture docs + demo script** for portfolio/interview presentation.

---

## 19. Important Design Tradeoffs

- **Choreography vs. orchestration**: chose choreography (services react to events) over a central
  saga-orchestrator service. Simpler for a 5-step linear flow; tradeoff is that the overall saga
  logic is spread across services rather than visible in one place — acceptable and worth explaining
  as a conscious choice in an interview, with orchestration named as the alternative for more complex
  sagas.
- **Optimistic vs. pessimistic locking for inventory**: chose optimistic (version column + retry)
  over `SELECT FOR UPDATE`. Better throughput under Kafka-consumer-driven concurrent load, no risk of
  long-held locks blocking consumer threads; tradeoff is added retry-loop complexity and a small
  chance of wasted work under very high contention on a single SKU.
- **Outbox + poller vs. Debezium/CDC**: chose a simple scheduled-poller outbox over full CDC
  infrastructure. Keeps the stack to what's already listed (no Kafka Connect/Debezium); tradeoff is
  slightly higher publish latency (poll interval) vs. true log-based CDC.
- **No API Gateway in v1**: frontend talks to each service directly. Simpler local dev, fewer moving
  parts; tradeoff is the frontend needs multiple base URLs and there's no single point for
  cross-cutting concerns like rate limiting — acceptable for a portfolio project, called out as a
  natural v2 addition.
- **JWT verified locally (RS256) vs. Auth-service token-introspection call**: chose local
  verification for lower latency and no hard runtime dependency on Auth being up; tradeoff is
  revocation is eventually-consistent (mitigated by short access-token TTL + refresh-token
  revocation list in Redis).
- **Single Postgres container, multiple databases (local dev only)** vs. one container per service:
  chosen for lower local resource usage; production deployment note that each would get its own
  managed instance.

---

## 20. Deliberately Deferred (Avoid Overengineering V1)

- API Gateway / BFF layer, centralized rate limiting.
- Saga orchestrator service / state-machine framework (e.g. Axon, Temporal) — choreography is enough
  at this scale.
- Automatic DLT reprocessing/replay tooling — manual replay documented instead.
- Real payment gateway integration (Stripe/etc.) — simulated payment only.
- Real email/SMS provider integration — Notification just logs/persists.
- CDC-based outbox (Debezium/Kafka Connect) — scheduled-poller outbox is enough.
- Multi-region/HA Kafka & Postgres topology, blue-green deploys, k8s/Helm — Docker Compose is the
  target for this project; Kubernetes manifests are a plausible "future work" bullet, not built now.
- Full OpenTelemetry distributed tracing across services — correlation-ID-based log correlation is
  the v1 answer; tracing (Zipkin/Jaeger) is a named future improvement.
- Admin UI beyond what's needed to demo — most admin actions can be exercised via REST client/Swagger.
- Fine-grained per-endpoint rate limiting / API quotas.
- Advanced fraud detection in Payment Service beyond the simple simulated approve/decline rule.

---

## 21. Known Local Environment Limitation: Testcontainers on Windows + Docker Desktop

Testcontainers-based integration tests (e.g. `AuthControllerIntegrationTest`) cannot currently run
reliably on this development machine (Windows, Docker Desktop 4.90). Running the same test in
complete isolation from OrderFlow (a throwaway JUnit test with no Spring context, no Postgres
container, just `DockerClientFactory.instance().isDockerAvailable()` and a bare
`GenericContainer("alpine:3.20")`) reproduces the identical failure 100% of the time (5/5 runs):

```
Could not find a valid Docker environment. ...
NpipeSocketClientProviderStrategy: failed with exception BadRequestException
(Status 400: {"message":"client version 1.32 is too old. Minimum supported API version is 1.40..."})
```

**Root cause isolation performed:**
- **Project/Maven configuration** — ruled out. The isolated diagnostic test has zero dependency on
  any OrderFlow code, POM setting, or test resource, and fails identically.
- **Stale Testcontainers strategy cache** (`~/.testcontainers.properties`) — ruled out. The cache was
  cleared to empty and the failure remained 100% reproducible across 5 independent JVM runs.
- **Docker context/socket configuration** — implicated but not fully fixable from the client side.
  The `docker` CLI itself works perfectly (`docker version`, `docker pull`, `docker ps` all succeed),
  and raw HTTP calls to `http://localhost:2375/version` succeed with real engine data. But
  Testcontainers' `docker-java` client is rejected at the handshake by an internal Docker Desktop
  gateway (self-identified via `com.docker.desktop.address=npipe://\\.\pipe\docker_cli`) that gates
  clients below its minimum API version, on both the classic named pipe
  (`\\.\pipe\docker_engine`) and, intermittently, Docker Desktop's "expose on
  `tcp://localhost:2375`" option. This is a Docker Desktop/Windows-environment behavior, not
  something a project-level `DOCKER_HOST`/`docker.host` override reliably works around — it was
  observed to sometimes get further (past the initial handshake, failing later on Ryuk's own
  internal image-resolution client instead) but never passed a full run consistently.

**Conclusion:** this is an environment-specific incompatibility between this Docker Desktop build
and the docker-java version bundled via testcontainers-bom 1.20.4 on native Windows, not a defect in
OrderFlow's code, POM, or test setup.

**Current approach:** Testcontainers-based integration tests are verified in CI (GitHub Actions
Ubuntu runners, which talk to Docker over its native Unix socket and are unaffected by this issue),
not on this local machine. All non-Testcontainers tests (unit tests, plain JUnit) run and pass
locally without issue.

**Update — WSL2 was tried and does not resolve this either.** A dedicated Ubuntu WSL2 distro was
installed, Docker Desktop's WSL Integration was enabled for it, and Java 21 + Maven were installed
inside it. Results:

- `docker version`/`docker ps` from inside WSL2: work perfectly (native Unix socket, real engine
  data) — confirms WSL2 itself and its Docker integration are fine.
- Java 21 (OpenJDK 21.0.12) and Maven 3.9.12 install and run fine via `apt`.
- The same zero-project-dependency standalone diagnostic, run from inside WSL2, **still fails**, but
  with more precise evidence once an SLF4J binding was added to actually surface Testcontainers'
  internal log output (the initial WSL2 runs silently swallowed the real cause under a NOP logger):

  ```
  UnixSocketClientProviderStrategy: failed with exception BadRequestException
  (Status 400: ... "Labels":["com.docker.desktop.address=unix:///var/run/docker-cli.sock"] ...)
  DockerDesktopClientProviderStrategy: failed with exception NullPointerException
  (Cannot invoke "java.nio.file.Path.toString()" because the return value of
  "org.testcontainers.dockerclient.DockerDesktopClientProviderStrategy.getSocketPath()" is null)
  ```

This sharpens the diagnosis considerably. Docker Desktop 4.90 fronts **both** its Windows named pipe
and its WSL2 Unix socket with the same internal `docker-cli` gateway, which version-gates raw API
clients identically on both transports. Worse, testcontainers-bom 1.20.4's `DockerDesktopClientProviderStrategy`
— the strategy specifically written to detect Docker Desktop and connect to its real engine socket,
bypassing that gateway — throws a `NullPointerException` because it can't locate the socket path at
all in this Docker Desktop version, i.e. Docker Desktop moved/renamed something internally that this
Testcontainers release doesn't yet know how to find.

**Revised conclusion:** this is a **version-compatibility gap between Docker Desktop 4.90 and
testcontainers-bom 1.20.4**, not an OrderFlow, Maven, or WSL2-configuration problem — WSL2 is a dead
end for this specific issue because Docker Desktop's backend serves both Windows and WSL2 through
the same versioned gateway/strategy-detection code.

**Update — testcontainers-bom bumped to 1.21.4 (pom.xml only, no application code changed).**
Maven Central was queried directly for available versions. `2.0.x` was ruled out despite being
newest: it removes JUnit 4 support and relocates container classes/artifacts (e.g.
`org.testcontainers.containers.PostgreSQLContainer` → a new package under a renamed
`testcontainers-postgresql` artifact), which would require editing test imports and the POM -
out of scope for a dependency-only update. `1.21.4`'s release notes state it specifically
"makes 1.21.x work with recent Docker Engine changes," and it keeps the exact 1.20.x package/artifact
surface OrderFlow's tests already use, so no code changes were needed.

Results with 1.21.4, from WSL2 (after also replacing the `apt`-installed Maven, which turned out to
have an unrelated packaging defect — its default `maven-surefire-plugin` binding is version 2.17, a
2013-era JUnit-4-only provider that silently reports "No tests were executed!" against JUnit 5
classes; the official Apache Maven 3.9.16 tarball resolves the modern 3.5.4 provider correctly and
was used for everything below):

- **Standalone diagnostic (zero project code):** passed twice in a row —
  `DockerClientFactory.instance().isDockerAvailable()` returned `true` with full real engine info,
  and a bare `GenericContainer("alpine:3.20")` started successfully with a real container ID.
- **`AuthControllerIntegrationTest` (the real project test, via `PostgreSQLContainer` +
  `@SpringBootTest`):** failed 3 times in a row immediately after, with the *identical* error seen
  before the version bump:
  ```
  UnixSocketClientProviderStrategy: failed with exception BadRequestException
  (Status 400: ... "Labels":["com.docker.desktop.address=unix:///var/run/docker-cli.sock"] ...)
  DockerDesktopClientProviderStrategy: failed with exception NullPointerException
  (Cannot invoke "java.nio.file.Path.toString()" because the return value of
  "org.testcontainers.dockerclient.DockerDesktopClientProviderStrategy.getSocketPath()" is null)
  ```
- Re-running the standalone diagnostic again immediately after those failures showed it now
  resolving Docker via a *different* internal path (`Found Docker environment with local Unix
  socket (unix:///var/run/docker.sock)` — bypassing the gated gateway entirely that time).

**Final assessment:** 1.21.4 measurably improved things (the isolated diagnostic went from 0/5
passes on 1.20.4 to 2/2 on 1.21.4), but Docker Desktop 4.90's socket/gateway routing is genuinely
**non-deterministic** on this machine, not just version-gated — which specific internal socket a
given process gets routed to varies run-to-run, independent of Testcontainers or the project code.
A fast, minimal diagnostic is more likely to catch a "good" routing window; a heavier Spring Boot
test that takes longer to reach the Docker call is more likely to catch a "bad" one. This is an
environment reliability issue in Docker Desktop itself, not something further POM or code changes
can deterministically fix.

**Current approach unchanged:** Testcontainers-based integration tests are verified in CI (GitHub
Actions Ubuntu runners, which run Docker directly with no Docker Desktop gateway in front of it, and
are unaffected by this). All non-Testcontainers tests continue to pass locally without issue. The
`testcontainers.version` bump to 1.21.4 is kept regardless, since it is a genuine improvement and
introduces no regressions.

**Resolution — CI confirms the design is sound.** Once pushed to GitHub Actions (Ubuntu, real
Docker, no Docker Desktop gateway), Testcontainers worked correctly on the first try and surfaced
two real, previously-hidden bugs that local flakiness had never let the test reach far enough to
find:

1. `TestRestTemplate` defaulted to the JDK's `HttpURLConnection` transport, which throws
   `HttpRetryException` ("cannot retry due to server authentication, in streaming mode") when a
   POST-with-body gets back a 401 — exactly what the "reused refresh token" assertion exercises.
   Fixed by adding `org.apache.httpcomponents.client5:httpclient5` as a test dependency so
   `RestTemplateBuilder` uses `HttpComponentsClientHttpRequestFactory` instead. Test-only change,
   no assertions touched.
2. `/logout` required a bearer access token in `SecurityConfig`, inconsistent with `/refresh` (which
   already treats possession of the refresh token itself as sufficient authorization). Fixed by
   adding `/logout` to the `permitAll` list, matching `/refresh`'s existing, RFC 7009-aligned model.

With both fixes, CI is green: **23/23 tests pass** (10 in `common`, 13 in `auth-service`, including
all 4 `AuthControllerIntegrationTest` cases). This is the authoritative, machine-independent proof
that the Testcontainers setup, the auth flow, and the reactor build are all correct — the entire
local Docker Desktop saga above was purely an artifact of this one Windows machine's Docker Desktop
installation, never a defect in OrderFlow itself.
