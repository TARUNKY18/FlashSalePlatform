# ClickHouse.md
## Flash Sale Platform — ClickHouse Reference
**Audience:** Interview preparation
**Source:** `deployment/docker/docker-compose.yml` · `deployment/docker/init-scripts/clickhouse/01-init.sql`
         · `docs/adr/01-Decisions.md` (ADR-018)
**Verified:** `curl http://localhost:8123/ping` → `Ok.`

---

## Table of Contents

1. [Why ClickHouse exists in this platform](#1-why-clickhouse-exists)
2. [Row vs column storage](#2-row-vs-column-storage)
3. [Your actual schema](#3-your-actual-schema)
4. [Query comparison — sales analytics](#4-query-comparison--sales-analytics)
5. [Query comparison — top products](#5-query-comparison--top-products)
6. [Query comparison — revenue trends](#6-query-comparison--revenue-trends)
7. [Three things ClickHouse gives you that Postgres cannot](#7-three-things-clickhouse-gives-you-that-postgres-cannot)
8. [ClickHouse in your docker-compose.yml](#8-clickhouse-in-your-docker-composeyml)
9. [Interview questions](#9-interview-questions)

---

## 1. Why ClickHouse Exists

### The problem with using PostgreSQL for analytics

Your architecture has three Postgres instances — `sales_db`, `inventory_db`, `orders_db`. Zero cross-database joins are permitted by design (ADR-008). To answer "what was the revenue per product per sale?" you would need to:

1. Query `sales_db` for sale metadata
2. Query `inventory_db` for reservation counts
3. Query `orders_db` for order totals
4. Assemble the result in Java code

This is application-level assembly across three databases. It is slow, fragile, and wrong.

The second problem: analytics queries compete with OLTP workload. During a live flash sale at 50,000 RPS, every CPU cycle on `inventory_db` is reserved for `SELECT FOR UPDATE` on stock. A `GROUP BY` query on the same database instance competes for the same I/O, WAL writer, and buffer pool. It degrades reservation P99 during peak load.

### The solution

AnalyticsService consumes all three Kafka topics (`sale-events`, `inventory-events`, `order-events`) and writes every event into one ClickHouse table: `flash_sale.sale_events`. One wide table across all services. No cross-service joins needed. A completely separate container — no shared resources with the OLTP path.

---

## 2. Row vs Column Storage

This is the fundamental reason ClickHouse is faster for analytics.

### PostgreSQL — row-oriented

Every row is stored contiguously on disk:

```
Row 1: [event_id=uuid1][event_type=RESERVED][sale_id=abc][user_id=u1][amount=299.00][occurred_at=...]
Row 2: [event_id=uuid2][event_type=RESERVED][sale_id=abc][user_id=u2][amount=299.00][occurred_at=...]
Row 3: [event_id=uuid3][event_type=CONFIRMED][sale_id=abc][user_id=u1][amount=299.00][occurred_at=...]
```

Query: `SELECT COUNT(*) WHERE event_type = 'RESERVED'`

Postgres must read every column in every row to find the matching rows — even though you asked about `event_type` only. At 10 million rows, it reads all 10 million complete rows from disk.

### ClickHouse — column-oriented

Each column is stored contiguously on disk:

```
event_type column:  [RESERVED][RESERVED][CONFIRMED][RESERVED][CONFIRMED][RESERVED]...
sale_id column:     [abc][abc][abc][def][def][def]...
amount column:      [299.00][299.00][299.00][149.00][149.00][149.00]...
occurred_at column: [09:00][09:00][09:01][09:02][09:02][09:03]...
```

Same query: `SELECT COUNT(*) WHERE event_type = 'RESERVED'`

ClickHouse reads only the `event_type` column from disk. The other columns are never touched. At 10 million rows, it reads 1 column instead of 8. That is the speedup.

### The multiplier effect

Columnar storage also compresses far better. `event_type` holds values like `RESERVED`, `CONFIRMED`, `EXPIRED`, `RELEASED` — a handful of repeated strings across millions of rows. ClickHouse's `LowCardinality(String)` stores this as a dictionary-encoded integer. The entire `event_type` column for 10 million rows fits in a few megabytes. Postgres stores the full string bytes for every row.

---

## 3. Your Actual Schema

```sql
-- deployment/docker/init-scripts/clickhouse/01-init.sql
CREATE TABLE flash_sale.sale_events
(
    event_id      String,
    event_type    LowCardinality(String),   -- RESERVED | CONFIRMED | EXPIRED | ...
    event_version LowCardinality(String),
    sale_id       String,
    user_id       String,
    occurred_at   DateTime64(3, 'UTC'),
    ingested_at   DateTime64(3, 'UTC') DEFAULT now64(3),
    raw_payload   String,                   -- full JSON from Kafka message

    INDEX idx_sale_id    sale_id    TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_event_type event_type TYPE set(10)            GRANULARITY 4
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(occurred_at)           -- separate data files per month
ORDER BY (sale_id, occurred_at, event_id)    -- rows physically sorted by this key
TTL occurred_at + INTERVAL 90 DAY;          -- auto-expire data older than 90 days
```

### Why each decision matters

**`LowCardinality(String)` for `event_type`**
Stores as dictionary-encoded integers internally. There are ~10 distinct event types across millions of rows. String comparison becomes integer comparison. Dramatically faster `WHERE event_type = ?` filters and `countIf(event_type = ?)` aggregations.

**`PARTITION BY toYYYYMM(occurred_at)`**
ClickHouse creates separate physical data files per month. A query with `WHERE occurred_at >= now() - 30 DAY` physically skips all files from older months without reading them. For a flash sale analytics dashboard showing the last 30 days, only 1–2 months of files are touched.

**`ORDER BY (sale_id, occurred_at, event_id)`**
Rows within each partition are physically sorted by this key. A `WHERE sale_id = ?` query skips most of the file using the sort order. Without this, every `sale_id` filter would scan the full partition.

**`INDEX idx_sale_id TYPE bloom_filter(0.01)`**
A probabilistic index with 1% false positive rate. For point lookups on `sale_id` (e.g. analytics for one specific sale), ClickHouse checks the bloom filter first and skips entire data granules that definitely do not contain the target `sale_id`. Very fast for single-sale drill-downs.

**`raw_payload String`**
The full JSON event payload from Kafka. ClickHouse stores it as-is. Analytics queries extract fields lazily with `JSONExtractString(raw_payload, 'productName')` and `JSONExtractFloat(raw_payload, 'amount')`. This means no schema migration is needed when the event payload schema evolves — new fields are available immediately via JSON extraction.

**`TTL occurred_at + INTERVAL 90 DAY`**
ClickHouse automatically expires and physically deletes rows older than 90 days. No manual cleanup job. No VACUUM equivalent needed. Storage stays bounded.

---

## 4. Query Comparison — Sales Analytics

**Question:** How many reservations and orders did each sale generate, and what was the conversion rate?

### PostgreSQL — why it cannot answer this query

```sql
-- IMPOSSIBLE in your architecture:
-- sales_db has flash_sales
-- inventory_db has reservations
-- orders_db has orders
-- Zero cross-database JOINs permitted (ADR-008)
-- Application must assemble from three separate queries

-- Even in a single hypothetical database, at scale:
SELECT
    fs.id       AS sale_id,
    fs.name,
    COUNT(DISTINCT r.id) AS total_reservations,
    COUNT(DISTINCT o.id) AS total_orders,
    ROUND(COUNT(DISTINCT o.id)::NUMERIC
          / NULLIF(COUNT(DISTINCT r.id), 0) * 100, 1) AS conversion_pct
FROM flash_sales fs
LEFT JOIN reservations r ON r.sale_id = fs.id   -- cross-DB: impossible
LEFT JOIN orders o       ON o.sale_id = fs.id   -- cross-DB: impossible
GROUP BY fs.id, fs.name
ORDER BY total_reservations DESC;

-- At 10M reservations + 8M orders:
-- Sequential scan of both tables → hash join → group
-- Estimated: 45–120 seconds
-- Holds locks on hot tables during execution
```

### ClickHouse — one query, sub-second

```sql
SELECT
    sale_id,
    countIf(event_type = 'STOCK_RESERVED')   AS total_reservations,
    countIf(event_type = 'ORDER_CONFIRMED')  AS total_orders,
    round(
        countIf(event_type = 'ORDER_CONFIRMED') * 100.0
        / nullIf(countIf(event_type = 'STOCK_RESERVED'), 0)
    , 1)                                     AS conversion_pct
FROM flash_sale.sale_events
WHERE occurred_at >= now() - INTERVAL 30 DAY   -- partition pruning: skips old months
GROUP BY sale_id
ORDER BY total_reservations DESC
LIMIT 20;

-- At 10M rows: ~500ms
-- Reads only: event_type column + sale_id column + occurred_at column (for partition pruning)
-- Touches only last 1-2 months of data files
-- Zero impact on OLTP containers
```

---

## 5. Query Comparison — Top Products

**Question:** Which products had the fastest sell-through during the flash sale window, and what was the reservation velocity in the first 5 minutes?

### PostgreSQL — why it is catastrophically slow at scale

```sql
-- The correlated subquery (LIMIT total_stock/2 per product)
-- re-executes once per product row. At 500 products × 10M reservations:

SELECT
    p.name                                         AS product_name,
    fs.name                                        AS sale_name,
    COUNT(r.id)                                    AS units_reserved,
    p.total_stock,
    ROUND(COUNT(r.id)::NUMERIC / p.total_stock * 100, 1) AS sellthrough_pct,
    -- Correlated subquery: re-runs 500 times
    MIN(r.created_at) FILTER (
        WHERE r.id IN (
            SELECT id FROM reservations r2
            WHERE r2.sale_id = fs.id
            ORDER BY r2.created_at
            LIMIT (p.total_stock / 2)
        )
    )                                              AS half_sold_at
FROM products p
JOIN flash_sales fs ON fs.product_id = p.id
JOIN reservations r ON r.product_id  = p.id
GROUP BY p.id, p.name, fs.id, fs.name, p.total_stock
ORDER BY sellthrough_pct DESC
LIMIT 10;

-- At 500 products × 10M reservations: correlated subquery = 500 full scans
-- Estimated: 5–12 minutes
-- Would be killed by lock_timeout=5000 on inventory_db
-- pg_stat_activity shows this holding shared locks during live sale
```

### ClickHouse — all in one scan

```sql
SELECT
    JSONExtractString(raw_payload, 'productName')  AS product_name,
    sale_id,
    countIf(event_type = 'STOCK_RESERVED')         AS units_reserved,
    countIf(event_type = 'ORDER_CONFIRMED')        AS units_sold,
    minIf(occurred_at, event_type = 'STOCK_RESERVED') AS first_reservation_at,
    round(
        countIf(event_type = 'ORDER_CONFIRMED') * 100.0
        / nullIf(countIf(event_type = 'STOCK_RESERVED'), 0)
    , 1)                                            AS sellthrough_pct,
    -- Velocity: reservations per minute in the first 5 minutes
    countIf(
        event_type = 'STOCK_RESERVED'
        AND occurred_at < minIf(occurred_at,
                               event_type = 'STOCK_RESERVED')
                          + INTERVAL 5 MINUTE
    ) / 5.0                                         AS reservations_per_min_first5
FROM flash_sale.sale_events
WHERE occurred_at >= today() - 7
  AND event_type IN ('STOCK_RESERVED', 'ORDER_CONFIRMED')
GROUP BY product_name, sale_id
ORDER BY sellthrough_pct DESC, units_reserved DESC
LIMIT 10;

-- At 10M rows: ~800ms
-- set(10) index on event_type pre-filters to just 2 event types before scanning
-- No correlated subquery — minIf() is a single-pass window aggregate
-- raw_payload JSON extraction happens only for matched rows
```

---

## 6. Query Comparison — Revenue Trends

**Question:** Revenue and order volume per hour over the last 7 days, broken down by sale.

### PostgreSQL — competes with live OLTP during query

```sql
-- orders_db has the revenue data
-- Partial index on (confirmed_at, status) helps — but still 7 days of scans

SELECT
    date_trunc('hour', o.confirmed_at)  AS hour,
    o.sale_id,
    COUNT(*)                             AS order_count,
    SUM(o.amount)                        AS revenue,
    AVG(o.amount)                        AS avg_order_value,
    SUM(SUM(o.amount)) OVER (
        PARTITION BY o.sale_id
        ORDER BY date_trunc('hour', o.confirmed_at)
    )                                    AS running_revenue
FROM orders o
WHERE o.confirmed_at >= NOW() - INTERVAL '7 days'
  AND o.status = 'CONFIRMED'
GROUP BY date_trunc('hour', o.confirmed_at), o.sale_id
ORDER BY hour, o.sale_id;

-- At 8M orders over 7 days: 2–8 seconds per query
-- During flash sale peak: index contention with concurrent INSERTs
-- Each dashboard auto-refresh slows reservation path
-- No cross-service data (no product names, no reservation comparison)
```

### ClickHouse — runs during live sale with zero OLTP impact

```sql
SELECT
    toStartOfHour(occurred_at)              AS hour,
    sale_id,
    countIf(event_type = 'ORDER_CONFIRMED') AS order_count,
    sumIf(
        JSONExtractFloat(raw_payload, 'amount'),
        event_type = 'ORDER_CONFIRMED'
    )                                        AS revenue,
    avgIf(
        JSONExtractFloat(raw_payload, 'amount'),
        event_type = 'ORDER_CONFIRMED'
    )                                        AS avg_order_value,
    sum(revenue) OVER (
        PARTITION BY sale_id
        ORDER BY hour
    )                                        AS running_revenue
FROM flash_sale.sale_events
WHERE occurred_at >= now() - INTERVAL 7 DAY
  AND event_type = 'ORDER_CONFIRMED'
GROUP BY hour, sale_id
ORDER BY hour ASC, revenue DESC;

-- At 10M rows: ~400ms
-- Only ORDER_CONFIRMED rows processed (set(10) index eliminates other types)
-- Runs on separate CPU/I/O from inventory_db, orders_db, kafka
-- Dashboard can auto-refresh every second during live sale with zero latency impact
```

---

## 7. Three Things ClickHouse Gives You That Postgres Cannot

### 1. Cross-service data without cross-service joins

Your architecture forbids cross-database joins (ADR-008). Three Postgres instances, zero shared tables. ClickHouse sidesteps this entirely — AnalyticsService writes events from all three Kafka topics into one table. Revenue (`amount` from order events), product names (`productName` from inventory events), and sale metadata (`saleName` from sale events) all land in `sale_events`. One query sees everything.

### 2. Zero OLTP impact — separate container, separate I/O

`flash-sale-clickhouse` is a completely independent container. Its CPU is not shared with `inventory_db`. Its disk I/O does not compete with the reservation path. During a flash sale at 50,000 RPS, you can run complex analytics queries continuously with zero effect on reservation P99 latency. The reverse is also true — a reservation storm does not degrade analytics query performance.

### 3. Built-in optimisations for the exact workload

| Feature | What it does | Why Postgres cannot match it |
|---|---|---|
| `LowCardinality(String)` | Dictionary-encodes repeated strings | Postgres TEXT is raw bytes — no automatic dictionary encoding |
| `PARTITION BY toYYYYMM` | Physically separates data by month | Postgres table partitioning helps but requires explicit management |
| Bloom filter index | Skips entire data granules for `sale_id` lookups | No native bloom filter skip index in Postgres |
| `countIf` / `sumIf` | Single-pass conditional aggregation | Postgres requires `COUNT(CASE WHEN... END)` — readable but slower |
| Vectorised execution | SIMD operations on columnar data | Postgres row-at-a-time executor cannot vectorise across rows |
| TTL expiry | Automatic data deletion after 90 days | Postgres requires explicit `DELETE` + `VACUUM` + `AUTOVACUUM` |

---

## 8. ClickHouse in Your `docker-compose.yml`

```yaml
clickhouse:
  image: clickhouse/clickhouse-server:24.3.3-alpine
  ports:
    - "8123:8123"     # HTTP interface — JDBC driver + curl health checks
    - "19000:9000"    # Native TCP — remapped, host port 9000 was in use
  ulimits:
    nofile:
      soft: 262144
      hard: 262144    # ClickHouse opens one file per column per shard
                      # Default Linux limit (1024) is hit immediately
  volumes:
    - clickhouse-data:/var/lib/clickhouse      # column data files
    - clickhouse-logs:/var/log/clickhouse-server  # server logs (separate volume)
```

**Port 19000, not 9000.** The host port 9000 was already in use on the development machine. The container still listens on 9000 internally — only the host-side mapping changed to 19000. The JDBC driver connects to `localhost:8123` (HTTP), not 9000. Only `clickhouse-client --native` uses the TCP port.

**`ulimits: nofile: 262144`.** ClickHouse stores each column in separate files. A table with 8 columns across multiple partitions can open hundreds of file descriptors simultaneously. The default Linux per-process limit of 1024 is exceeded on startup. Without this override, ClickHouse crashes with `Too many open files`.

**Two volumes.** `clickhouse-data` holds the column data files — the actual analytics storage. `clickhouse-logs` holds server logs separately so log growth cannot fill the data volume and vice versa. Both are named volumes — survive `make down`, destroyed by `make clean`.

**Health check:**
```bash
curl http://localhost:8123/ping
# Expected: Ok.
```

The HTTP interface returns `Ok.` immediately if the server is running. Your `health-check.sh` uses exactly this.

---

## 9. Interview Questions

**"Why does your architecture use ClickHouse instead of just querying PostgreSQL for analytics?"**
Three reasons. First, the architecture forbids cross-database joins — revenue is in `orders_db`, product data is in `inventory_db`, sale metadata is in `sales_db`. PostgreSQL cannot join across them. ClickHouse receives all events via Kafka and stores them in one table. Second, analytical queries compete with OLTP workload on the same Postgres I/O — during a live sale at 50k RPS, a GROUP BY query would degrade reservation latency. ClickHouse is a separate container with no shared resources. Third, columnar storage reads only the queried columns, making aggregation queries 10–100× faster than row-oriented Postgres at 10M+ rows.

**"What is the difference between row-oriented and column-oriented storage?"**
In PostgreSQL, each row is stored contiguously — all columns of row 1, then all columns of row 2. A query touching one column still reads every row completely. In ClickHouse, each column is stored contiguously — all values of `event_type`, then all values of `sale_id`, etc. A query on `event_type` reads only that column's files, skipping the rest entirely. At 10M rows with 8 columns, a single-column filter in ClickHouse reads 1/8th the data Postgres would.

**"What is `LowCardinality(String)` and why does your schema use it?"**
It tells ClickHouse to dictionary-encode the column — internally stored as an integer mapping to a small lookup table of unique strings. `event_type` has ~10 distinct values (`STOCK_RESERVED`, `ORDER_CONFIRMED`, `SALE_STARTED`, etc.) across millions of rows. Storing and comparing integers is far faster than comparing raw strings, and the compression ratio is dramatically better. A `countIf(event_type = 'ORDER_CONFIRMED')` over 10M rows becomes integer comparison over a dictionary-encoded compact column.

**"What is `PARTITION BY toYYYYMM(occurred_at)` and what does it save?"**
ClickHouse creates separate physical data files per month. A query with `WHERE occurred_at >= now() - 30 DAY` physically skips every data file from months outside that range without reading them. For a dashboard showing the last 30 days of analytics, only 1–2 months of files are opened. 90 days of historical data, 90-day TTL — only 3 months of files exist at any time. Most queries touch 1–2 of them.

**"What is the bloom filter index on `sale_id` doing?"**
A bloom filter is a probabilistic structure that answers "is this value definitely absent from this data block?" with zero false negatives and 1% false positives (as configured). Before reading a data granule (8192 rows), ClickHouse checks the bloom filter. If it says "this `sale_id` is definitely not here," the entire granule is skipped. For single-sale drill-down queries, most granules are skipped, reading only the ~1% that might contain the target `sale_id`.

**"How does AnalyticsService write to ClickHouse without affecting the transactional path?"**
AnalyticsService is a pure Kafka consumer — it reads from `sale-events`, `inventory-events`, and `order-events`, writes to ClickHouse in micro-batches (1,000 events or 1 second), and has no connection to PostgreSQL. An AnalyticsService outage or a ClickHouse crash has zero effect on reservation, inventory, or order creation. The events accumulate in Kafka (3-day retention) and are replayed when AnalyticsService recovers. This is the temporal decoupling that ADR-006 was designed to achieve.

**"Why is `raw_payload` stored as a String rather than a structured JSON type?"**
Deliberate flexibility tradeoff. Event schemas evolve — new fields are added as the platform grows. If `raw_payload` were a structured type, every new field would require a schema migration on a table with millions of rows. As a `String`, new fields appear in `raw_payload` immediately and are accessible via `JSONExtractString(raw_payload, 'newField')` with no migration. The cost is that JSON extraction at query time is slightly slower than direct column access. For analytics latency targets (< 1 second), this tradeoff is acceptable.

---

*Source: ADR-018 (ClickHouse over PostgreSQL),*
*`deployment/docker/init-scripts/clickhouse/01-init.sql` (verified schema).*
*All decisions in `docs/adr/01-Decisions.md`.*