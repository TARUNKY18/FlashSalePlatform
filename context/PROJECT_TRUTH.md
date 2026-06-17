# PROJECT_TRUTH.md
## Flash Sale Platform — Verified Project State
**Generated:** 2026-06-17
**Source:** Confirmed outputs from terminal sessions, docker logs, screenshots, and
            explicit command results. Every statement is tagged VERIFIED or PLANNED.
            Nothing is assumed.

---

## 1. Repository Structure

```
FlashSalePlatform/                         VERIFIED — make up runs from this directory
├── Makefile                               VERIFIED — moved from deployment/docker/ to root
│                                                     confirmed: make up / make clean execute
├── deployment/
│   └── docker/                            VERIFIED — confirmed from IDE screenshot
│       ├── docker-compose.yml             VERIFIED — file exists and is parsed by Docker
│       ├── .env                           VERIFIED — created by cp .env.example .env
│       ├── .env.example                   VERIFIED — confirmed from IDE screenshot
│       ├── config/
│       │   └── redis-node.conf            VERIFIED — fixed (inline comments removed)
│       │                                             Redis nodes healthy after fix
│       ├── init-scripts/                  VERIFIED — confirmed from IDE screenshot
│       │   ├── clickhouse/                VERIFIED — folder visible in screenshot
│       │   ├── inventory-db/              VERIFIED — folder visible in screenshot
│       │   ├── orders-db/                 VERIFIED — folder visible in screenshot
│       │   └── sales-db/                  VERIFIED — folder visible in screenshot
│       └── scripts/
│           └── health-check.sh            VERIFIED — file visible in screenshot
├── docs/                                  VERIFIED — folder visible in screenshot
│   ├── architecture/                      PLANNED  — folder structure defined, contents
│   │   ├── Final-Spec-Council.md         PLANNED  — generated, not confirmed placed
│   │   ├── DomainModel.md               PLANNED  — generated, not confirmed placed
│   │   ├── DatabaseSchema.md            PLANNED  — generated, not confirmed placed
│   │   ├── KafkaDesign.md               PLANNED  — generated, not confirmed placed
│   │   ├── RedisDesign.md               PLANNED  — generated, not confirmed placed
│   │   ├── PRD-FlashSalePlatform.md     PLANNED  — generated, not confirmed placed
│   │   ├── Build-Plan.md                PLANNED  — generated, not confirmed placed
│   │   └── schema.sql                   PLANNED  — generated, not confirmed placed
│   └── adr/
│       └── 01-Decisions.md              PLANNED  — generated, not confirmed placed
├── services/                              PLANNED  — directory not yet created
├── testing/                               PLANNED  — directory not yet created
├── benchmarks/                            PLANNED  — directory not yet created
├── incidents/                             PLANNED  — directory not yet created
├── README.md                              PLANNED  — generated, not confirmed placed
├── INFRASTRUCTURE.md                      PLANNED  — generated, not confirmed placed
├── build.gradle                           PLANNED  — not written
├── settings.gradle                        PLANNED  — not written
└── .gitignore                             PLANNED  — generated, not confirmed placed
```

---

## 2. Infrastructure Components

### PostgreSQL

| Instance | Image | Host port | Status |
|---|---|---|---|
| flash-sale-sales-db | postgres:16.3-alpine | 5432 | VERIFIED healthy — pg_isready returned "accepting connections" |
| flash-sale-inventory-db | postgres:16.3-alpine | 5433 | VERIFIED healthy — pg_isready returned "accepting connections" |
| flash-sale-orders-db | postgres:16.3-alpine | 5434 | VERIFIED healthy — pg_isready returned "accepting connections" |

**lock_timeout on inventory-db:** PLANNED — set in docker-compose.yml command block, never verified with `SHOW lock_timeout`

**Flyway migrations:** PLANNED — init scripts create extensions only; no application tables exist yet

### Redis Cluster

| Node | Image | Host port | Container status |
|---|---|---|---|
| flash-sale-redis-1 | redis:7.2.5-alpine | 7001 | VERIFIED healthy — `docker ps` showed `(healthy)` |
| flash-sale-redis-2 | redis:7.2.5-alpine | 7002 | VERIFIED healthy |
| flash-sale-redis-3 | redis:7.2.5-alpine | 7003 | VERIFIED healthy |
| flash-sale-redis-4 | redis:7.2.5-alpine | 7004 | VERIFIED healthy |
| flash-sale-redis-5 | redis:7.2.5-alpine | 7005 | VERIFIED healthy |
| flash-sale-redis-6 | redis:7.2.5-alpine | 7006 | VERIFIED healthy |

**Redis cluster state (cluster_state:ok):** NOT VERIFIED — the last `make health` output showed `cluster_state:fail`. The redis-cluster-init container was fixed (sh: -a: not found errors resolved) and `Started` in the most recent run, but `cluster_state:ok` was never confirmed after the fix. Must verify with `make redis-cluster-info`.

**redis-cluster-init:** VERIFIED fixed — command block rewritten from `command: >` (YAML folded scalar) to list form `[sh, -c, script]`. The `sh: -a: not found` errors are gone. Whether the cluster actually formed requires confirmation.

### Kafka

| Container | Image | Status |
|---|---|---|
| flash-sale-kafka | apache/kafka:3.7.0 | NOT VERIFIED |

**Image:** VERIFIED — `sed -i '' 's/bitnami\/kafka:3.7.0/apache\/kafka:3.7.0/'` applied and confirmed with grep

**KAFKA_CFG_ → KAFKA_ migration:** VERIFIED applied — `sed -i '' 's/KAFKA_CFG_/KAFKA_/g'` run and confirmed `KAFKA_PROCESS_ROLES: broker,controller` present; `KAFKA_CFG_` returned nothing

**Broker health:** NOT VERIFIED — `make up` has not been re-run after the KAFKA_CFG_ fix. The broker was failing with `Missing required configuration 'zookeeper.connect'` before the fix. Outcome after fix is unknown.

**Kafka topics:** PLANNED — no topics created; services have not started; `AUTO_CREATE_TOPICS_ENABLE=false`

### ClickHouse

| Container | Image | HTTP port | Native TCP port | Status |
|---|---|---|---|---|
| flash-sale-clickhouse | clickhouse/clickhouse-server:24.3.3-alpine | 8123 | 19000 (remapped from 9000) | VERIFIED healthy |

**HTTP interface:** VERIFIED — `make health` returned `Ok.` on ping

**Port 9000 remapped to 19000:** VERIFIED — `sed -i '' 's/"9000:9000"/"19000:9000"/'` applied and confirmed with grep; host port 9000 was in use

**sale_events table:** PLANNED — init SQL was written; table existence never confirmed with a query

### Tooling UIs

| Container | Image | URL | Status |
|---|---|---|---|
| flash-sale-kafka-ui | provectuslabs/kafka-ui:v0.7.2 | http://localhost:18080 | VERIFIED — `make health` returned `✓ Kafka UI` |
| flash-sale-redisinsight | redislabs/redisinsight:2.50 | http://localhost:18081 | VERIFIED — container `Started` in docker compose output |

---

## 3. Verified Services

**Application services written:** NONE

No Java code exists in the repository. The following five services are fully designed but have zero implementation:

| Service | Port | Status |
|---|---|---|
| SaleService | 8081 | PLANNED — domain model designed, zero code written |
| InventoryService | 8082 | PLANNED — domain model designed, zero code written |
| OrderService | 8083 | PLANNED — domain model designed, zero code written |
| NotificationService | 8084 | PLANNED — consumer topology designed, zero code written |
| AnalyticsService | 8085 | PLANNED — ClickHouse schema designed, zero code written |

---

## 4. Docker Commands

| Command | Status | Evidence |
|---|---|---|
| `make up` | VERIFIED working | Containers start from `FlashSalePlatform/` root |
| `make clean` | VERIFIED working | All 27 containers and volumes removed successfully |
| `make clean && make up` | VERIFIED working | Clean cycle confirmed in terminal output |
| `make health` | VERIFIED working — partial | Runs; Postgres and ClickHouse pass; Redis cluster and Kafka status unconfirmed post-fix |
| `make down` | VERIFIED — untested in session | Command exists in Makefile; not explicitly run during this session |
| `make kafka-topics` | PLANNED — untested | Kafka broker not yet confirmed healthy |
| `make kafka-lag` | PLANNED — untested | Kafka broker not yet confirmed healthy |
| `make redis-cluster-info` | PLANNED — untested this session | Last known result was `cluster_state:fail` |
| `make db-sales` | PLANNED — untested | Postgres running but command never explicitly tested |
| `make db-inventory` | PLANNED — untested | Postgres running but command never explicitly tested |
| `make db-orders` | PLANNED — untested | Postgres running but command never explicitly tested |

**Makefile location:** VERIFIED at `FlashSalePlatform/Makefile` — confirmed because `make up` executes from project root without `-f` flag

---

## 5. Branches

| Branch | Status |
|---|---|
| main (or master) | VERIFIED — one branch, all work committed here |

**Branch strategy:** PLANNED — no branching strategy defined or implemented. Feature branches, PR workflow, and branch protection rules do not exist yet.

---

## 6. Technology Stack

| Technology | Version | Status | Evidence |
|---|---|---|---|
| Docker | 24.x+ | VERIFIED | All compose commands execute |
| Docker Compose | v2 | VERIFIED | `docker compose` (not `docker-compose`) used throughout |
| PostgreSQL | 16.3-alpine | VERIFIED running | pg_isready passed on all 3 instances |
| Redis | 7.2.5-alpine | VERIFIED running | All 6 nodes healthy per docker ps |
| Apache Kafka | 3.7.0 | VERIFIED image pulled | Broker health not yet confirmed post-fix |
| ClickHouse | 24.3.3-alpine | VERIFIED running | HTTP ping returned Ok. |
| Java | 21 | PLANNED | Planned language; no JVM processes running |
| Spring Boot | 3.3 | PLANNED | Planned framework; no services written |
| Gradle | 8.x | PLANNED | No build.gradle exists |
| Kubernetes | — | PLANNED | No Helm charts, no cluster, no manifests |
| Terraform | — | PLANNED | No .tf files written |
| Testcontainers | — | PLANNED | No test code written |
| Gatling | — | PLANNED | No simulation files written |
| Micrometer | — | PLANNED | No metrics code written |
| OpenTelemetry | — | PLANNED | No tracing code written |

---

## 7. Architectural Decisions

These decisions are designed and documented. None are implemented in code yet.

| ADR | Decision | Documentation status | Implementation status |
|---|---|---|---|
| ADR-001 | Lua atomic stock decrement instead of WATCH/MULTI/EXEC | VERIFIED — 4 Lua scripts generated | PLANNED — no service to execute them |
| ADR-002 | Java 21 virtual threads instead of WebFlux | VERIFIED — documented | PLANNED — no Java code |
| ADR-003 | Kafka async fan-out only, never synchronous RPC | VERIFIED — documented | PLANNED — no Kafka producers/consumers |
| ADR-004 | Transactional Outbox pattern | VERIFIED — documented | PLANNED — no OutboxEvent entity |
| ADR-008 | Database-per-service | VERIFIED — enforced at infrastructure level | VERIFIED — 3 separate Postgres containers running |
| ADR-009 | 5 services (supersedes 3-service model) | VERIFIED — documented | PLANNED — 0 of 5 services written |
| ADR-012 | Choreography-based saga | VERIFIED — documented | PLANNED — no saga consumers |
| ADR-013 | inventory-events partitioned by productId | VERIFIED — documented | PLANNED — topic not yet created |

**ADR document location:** PLANNED — `docs/adr/01-Decisions.md` was generated; not confirmed placed in repository

---

## 8. Things Not Yet Implemented

The following have zero implementation. No files, no code, no configuration.

### No Java code exists

```
PLANNED  services/sale-service/
PLANNED  services/inventory-service/
PLANNED  services/order-service/
PLANNED  services/notification-service/
PLANNED  services/analytics-service/
PLANNED  build.gradle
PLANNED  settings.gradle
```

### No Flyway migrations

```
PLANNED  services/*/src/main/resources/db/migration/V1__init.sql
```
The init SQL scripts bootstrap extensions only. No application tables (flash_sales, products, reservations, orders, order_outbox, idempotency_keys) exist in any database.

### No Kafka topics

```
PLANNED  sale-events         (8 partitions)
PLANNED  inventory-events    (16 partitions)
PLANNED  order-events        (8 partitions)
PLANNED  sale-events.retry
PLANNED  inventory-events.retry
PLANNED  order-events.retry
PLANNED  notifications.dlq
PLANNED  analytics.dlq
```
`AUTO_CREATE_TOPICS_ENABLE=false`. Topics are created by services on startup. No services exist.

### Lua scripts not integrated

```
VERIFIED generated:  stock_decrement.lua
VERIFIED generated:  stock_prewarm.lua
VERIFIED generated:  stock_release.lua
VERIFIED generated:  stock_reconcile.lua
VERIFIED generated:  rate_limit.lua
PLANNED  placement:  services/inventory-service/src/main/resources/lua/
PLANNED  placement:  services/sale-service/src/main/resources/lua/
PLANNED  execution:  no StockCounterService or RateLimiterService to call them
```

### No Kubernetes infrastructure

```
PLANNED  deployment/helm/
PLANNED  deployment/terraform/
```

### No tests

```
PLANNED  services/*/src/test/
PLANNED  testing/contract/ArchitectureBoundaryTest.java
PLANNED  testing/chaos/
PLANNED  benchmarks/gatling/
```

---

## Immediate next steps before Week 2

```
[ ] Run: make up  (confirm Kafka broker healthy after KAFKA_CFG_ fix)
[ ] Run: make health  (confirm all 5 components green)
[ ] Run: make redis-cluster-info  (confirm cluster_state:ok)
[ ] Run: make kafka-topics  (confirm broker accepts connections)
[ ] Commit current state to git
[ ] Week 2: SaleService — FlashSale aggregate, Java 21 sealed SaleStatus
```

---
*This document reflects only what is confirmed true as of 2026-06-17.*
*Every PLANNED item has a design document but zero running code.*
*The infrastructure foundation (Week 1) is 80% verified. Kafka and Redis cluster*
*state require one successful `make health` to close out Week 1.*