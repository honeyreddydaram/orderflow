#!/usr/bin/env bash
# Real end-to-end smoke test of the OrderFlow saga against actual running Docker Compose
# services - no mocks, no Testcontainers. Every "integration test" in the per-service test
# suites verifies one service in isolation (its own Testcontainers Postgres/Kafka, a Kafka
# message the test itself published directly). This script instead proves what those
# structurally cannot: real services, over real Docker networking, through a shared Kafka
# broker and independent Postgres databases, actually work together - see
# docs/architecture.md, Milestone 8.
#
# Unlike every Testcontainers test in this repo, this script is NOT affected by the Docker
# Desktop/docker-java incompatibility documented in architecture.md section 21 - it only uses
# the plain docker/docker compose CLI and curl, which have worked reliably throughout that
# entire investigation. It can be run locally on this machine.
#
# Usage:   bash scripts/e2e-smoke-test.sh
# Requires: docker, docker compose, curl. No jq or local psql install needed - JSON fields are
# extracted with grep/sed, and DB assertions run through `docker compose exec postgres psql`.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

AUTH_URL="http://localhost:8081/api/v1/auth"
PRODUCT_URL="http://localhost:8082/api/v1/products"
ORDER_URL="http://localhost:8083/api/v1/orders"
INVENTORY_URL="http://localhost:8084/api/v1/inventory"

RUN_ID="$(date +%s)-$RANDOM"
PASS=0
FAIL=0

log() { echo "[e2e] $*"; }
pass() { PASS=$((PASS + 1)); echo "[PASS] $*"; }
fail() { FAIL=$((FAIL + 1)); echo "[FAIL] $*"; }

# ---- JSON helpers (no jq dependency - responses here are flat, single-level DTOs) ------------

json_str() { # json_str <fieldName>  (reads JSON from stdin)
  grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/'
}

psql_exec() { # psql_exec <db> <sql>
  docker compose exec -T postgres psql -U orderflow -d "$1" -tAc "$2" | tr -d '[:space:]'
}

# ---- Startup -----------------------------------------------------------------------------------
# Deliberately no EXIT trap: on failure, the stack is left running so `docker compose logs` (and,
# locally, `docker compose exec`) can actually inspect what happened - CI's runner is ephemeral
# and reclaims it regardless, and a local run's stack can be torn down with a plain
# `docker compose down -v` once you're done looking. Only a successful run tears itself down.

log "Building service images one at a time..."
# Not `docker compose up -d --build`: BuildKit parallelizes all six Maven/JDK build stages at
# once by default, which is memory-heavy enough to get the whole build killed on a
# resource-constrained dev machine. Building sequentially trades wall-clock time for a much
# lower peak memory footprint - the runtime containers started afterward are cheap by
# comparison (six lightweight JRE Alpine images, not six concurrent Maven builds).
for service in auth-service product-service order-service inventory-service payment-service notification-service; do
  log "Building $service..."
  docker compose build "$service"
done

log "Starting the stack (kafka-ui omitted - it's a human debugging aid, not needed for this script)..."
docker compose up -d postgres redis kafka auth-service product-service order-service \
  inventory-service payment-service notification-service

wait_for_health() { # wait_for_health <compose-service-name> <port>
  local service=$1 port=$2
  log "Waiting for $service to become healthy..."
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      log "$service is up"
      return 0
    fi
    sleep 3
  done
  echo "$service did not become healthy in time - last 100 log lines:"
  docker compose logs --tail=100 "$service"
  exit 1
}

wait_for_health auth-service 8081
wait_for_health product-service 8082
wait_for_health order-service 8083
wait_for_health inventory-service 8084
wait_for_health payment-service 8085
wait_for_health notification-service 8086

# ---- Bootstrap: an ADMIN and a CUSTOMER ---------------------------------------------------------
# There is no self-service way to become ADMIN via the API (by design). This promotes a
# freshly-registered user directly in Auth Service's own database, exactly as a real operator
# would when bootstrapping the first admin account - see docs/architecture.md section 13.

admin_username="e2e-admin-$RUN_ID"
customer_username="e2e-customer-$RUN_ID"

register() { # register <username>
  curl -sf -X POST "$AUTH_URL/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"email\":\"$1@example.com\",\"password\":\"Password123!\"}" >/dev/null
}

login() { # login <username>  -> prints accessToken
  curl -sf -X POST "$AUTH_URL/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"Password123!\"}" | json_str accessToken
}

register "$admin_username"
admin_user_id=$(psql_exec auth_db "SELECT id FROM users WHERE username = '$admin_username';")
psql_exec auth_db "INSERT INTO user_roles (user_id, role) VALUES ('$admin_user_id', 'ADMIN');" >/dev/null
admin_token=$(login "$admin_username")

register "$customer_username"
customer_token=$(login "$customer_username")

if [[ -n "$admin_token" ]]; then pass "Admin bootstrap"; else fail "Admin bootstrap"; exit 1; fi
if [[ -n "$customer_token" ]]; then pass "Customer registration/login"; else fail "Customer registration/login"; exit 1; fi

# ---- Helpers -------------------------------------------------------------------------------------

create_product_and_stock() { # create_product_and_stock <name> <price> <stock>  -> prints productId
  local name=$1 price=$2 stock=$3 product_json product_id
  product_json=$(curl -sf -X POST "$PRODUCT_URL" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $admin_token" \
    -d "{\"name\":\"$name\",\"description\":\"e2e test product\",\"price\":$price,\"stockQuantity\":$stock}")
  product_id=$(echo "$product_json" | json_str id)
  curl -sf -X POST "$INVENTORY_URL" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $admin_token" \
    -d "{\"productId\":\"$product_id\",\"availableQty\":$stock}" >/dev/null
  echo "$product_id"
}

create_order() { # create_order <token> <productId> <quantity>  -> prints orderId
  curl -sf -X POST "$ORDER_URL" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $1" \
    -d "{\"items\":[{\"productId\":\"$2\",\"quantity\":$3}]}" | json_str id
}

order_status() { # order_status <token> <orderId>  -> prints status
  curl -sf "$ORDER_URL/$2/status" -H "Authorization: Bearer $1" | json_str status
}

await_status() { # await_status <token> <orderId> <expectedStatus> <timeoutSeconds>
  local token=$1 orderId=$2 expected=$3 timeout=$4 waited=0 status=""
  while [[ $waited -lt $timeout ]]; do
    status=$(order_status "$token" "$orderId")
    if [[ "$status" == "$expected" ]]; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "Order $orderId did not reach $expected within ${timeout}s (last status: $status)"
  return 1
}

# ---- Scenario 1: happy path ----------------------------------------------------------------------

log "Scenario 1: happy path (order confirms)"
product_id=$(create_product_and_stock "E2E Widget" 9.99 10)
order_id=$(create_order "$customer_token" "$product_id" 2)

if await_status "$customer_token" "$order_id" CONFIRMED 30; then
  pass "Happy path: order reached CONFIRMED"
else
  fail "Happy path: order did not reach CONFIRMED"
fi

payment_status=$(psql_exec payment_db "SELECT status FROM payments WHERE order_id = '$order_id';")
if [[ "$payment_status" == "COMPLETED" ]]; then pass "Happy path: payment COMPLETED"; else fail "Happy path: payment status was '$payment_status'"; fi

available_qty=$(psql_exec inventory_db "SELECT available_qty FROM stock_items WHERE product_id = '$product_id';")
if [[ "$available_qty" == "8" ]]; then pass "Happy path: stock decremented to 8"; else fail "Happy path: available_qty was '$available_qty', expected 8"; fi

notification_count=$(psql_exec notification_db "SELECT count(*) FROM notifications WHERE order_id = '$order_id' AND type = 'ORDER_CONFIRMED';")
if [[ "$notification_count" == "1" ]]; then pass "Happy path: ORDER_CONFIRMED notification created"; else fail "Happy path: expected 1 ORDER_CONFIRMED notification, found $notification_count"; fi

# ---- Scenario 2: insufficient stock ---------------------------------------------------------------

log "Scenario 2: insufficient stock (order fails, no payment attempted)"
scarce_product_id=$(create_product_and_stock "E2E Scarce Widget" 5.00 1)
order2_id=$(create_order "$customer_token" "$scarce_product_id" 5)

if await_status "$customer_token" "$order2_id" FAILED 30; then
  pass "Insufficient stock: order reached FAILED"
else
  fail "Insufficient stock: order did not reach FAILED"
fi

payment2_count=$(psql_exec payment_db "SELECT count(*) FROM payments WHERE order_id = '$order2_id';")
if [[ "$payment2_count" == "0" ]]; then pass "Insufficient stock: no payment row created"; else fail "Insufficient stock: expected 0 payment rows, found $payment2_count"; fi

scarce_qty=$(psql_exec inventory_db "SELECT available_qty FROM stock_items WHERE product_id = '$scarce_product_id';")
if [[ "$scarce_qty" == "1" ]]; then pass "Insufficient stock: stock unchanged at 1"; else fail "Insufficient stock: available_qty was '$scarce_qty', expected 1"; fi

# ---- Scenario 3: payment declined (large amount) -----------------------------------------------

log "Scenario 3: payment declined (order fails after reservation, stock released)"
expensive_product_id=$(create_product_and_stock "E2E Expensive Widget" 20000.00 5)
order3_id=$(create_order "$customer_token" "$expensive_product_id" 1)

if await_status "$customer_token" "$order3_id" FAILED 30; then
  pass "Payment declined: order reached FAILED"
else
  fail "Payment declined: order did not reach FAILED"
fi

expensive_qty=$(psql_exec inventory_db "SELECT available_qty FROM stock_items WHERE product_id = '$expensive_product_id';")
if [[ "$expensive_qty" == "5" ]]; then pass "Payment declined: stock released back to 5"; else fail "Payment declined: available_qty was '$expensive_qty', expected 5"; fi

payment3_status=$(psql_exec payment_db "SELECT status FROM payments WHERE order_id = '$order3_id';")
if [[ "$payment3_status" == "FAILED" ]]; then pass "Payment declined: payment FAILED recorded"; else fail "Payment declined: payment status was '$payment3_status'"; fi

notification3_count=$(psql_exec notification_db "SELECT count(*) FROM notifications WHERE order_id = '$order3_id' AND type = 'ORDER_FAILED';")
if [[ "$notification3_count" == "1" ]]; then pass "Payment declined: ORDER_FAILED notification created"; else fail "Payment declined: expected 1 ORDER_FAILED notification, found $notification3_count"; fi

# ---- Scenario 4: system-level Idempotency-Key replay -------------------------------------------

log "Scenario 4: Idempotency-Key replay with real services running"
idem_product_id=$(create_product_and_stock "E2E Idempotency Widget" 9.99 10)
idem_key="e2e-idem-$RUN_ID"

first_id=$(curl -sf -X POST "$ORDER_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $customer_token" -H "Idempotency-Key: $idem_key" \
  -d "{\"items\":[{\"productId\":\"$idem_product_id\",\"quantity\":1}]}" | json_str id)
second_id=$(curl -sf -X POST "$ORDER_URL" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $customer_token" -H "Idempotency-Key: $idem_key" \
  -d "{\"items\":[{\"productId\":\"$idem_product_id\",\"quantity\":1}]}" | json_str id)

if [[ "$first_id" == "$second_id" ]]; then pass "Idempotency-Key replay: same order id returned"; else fail "Idempotency-Key replay: got '$first_id' then '$second_id'"; fi

order_count=$(psql_exec order_db "SELECT count(*) FROM orders WHERE id = '$first_id';")
if [[ "$order_count" == "1" ]]; then pass "Idempotency-Key replay: exactly one order row"; else fail "Idempotency-Key replay: found $order_count order rows"; fi

# ---- Summary --------------------------------------------------------------------------------------

log "Results: $PASS passed, $FAIL failed"
if [[ $FAIL -gt 0 ]]; then
  log "Leaving the stack running for inspection - 'docker compose logs <service>' or"
  log "'docker compose exec postgres psql -U orderflow -d <db>' to see what happened, then"
  log "'docker compose down -v' when done."
  exit 1
fi

log "Tearing down docker compose stack..."
docker compose down -v >/dev/null 2>&1 || true
