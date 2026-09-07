-- Creates one logical database per service (database-per-service ownership, see
-- docs/architecture.md section 3). A single Postgres container hosts all of them in local
-- dev/CI; each would be its own managed instance in a real deployment.
CREATE DATABASE auth_db;
CREATE DATABASE product_db;
CREATE DATABASE order_db;
CREATE DATABASE inventory_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
