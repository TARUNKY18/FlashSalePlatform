# REPOSITORY_INDEX.md
## Flash Sale Platform — Repository Directory Index
**Generated:** 2026-06-17
**Basis:** IDE screenshot, terminal output, explicit file confirmations.
**Rule:** No directory or file is listed unless its existence is confirmed.

---

## Root — `FlashSalePlatform/`

| Item | Status | Evidence |
|---|---|---|
| `Makefile` | **Existing** | `make up` / `make clean` / `make health` execute from this directory |
| `deployment/` | **Existing** | Visible in IDE screenshot |
| `docs/` | **Existing** | Visible in IDE screenshot (collapsed — contents unconfirmed) |
| `services/` | **Planned** | Not visible in screenshot; no Java code written |
| `testing/` | **Planned** | Not visible in screenshot; no test code written |
| `benchmarks/` | **Planned** | Not visible in screenshot; no load tests written |
| `incidents/` | **Planned** | Not visible in screenshot; PM-001 generated but placement unconfirmed |
| `README.md` | **Planned** | Generated in session; not confirmed placed at root |
| `INFRASTRUCTURE.md` | **Planned** | Generated in session; not confirmed placed |
| `CURRENT_STATE.md` | **Planned** | Generated this session; not yet committed |
| `PROJECT_TRUTH.md` | **Planned** | Generated this session; not yet committed |
| `build.gradle` | **Planned** | Not written; no Gradle project exists |
| `settings.gradle` | **Planned** | Not written |
| `.gitignore` | **Planned** | Generated in session; not confirmed placed |

---

## `deployment/`

**Purpose:** Everything needed to run the platform in any environment.
**Owner:** Infrastructure / Platform.
**Status:** Partially existing — Docker layer complete; Helm and Terraform not started.

---

### `deployment/docker/` — **Existing**

**Purpose:** Full local development stack. Single source of truth for running all
infrastructure on a developer machine.
**Owner:** All services depend on this; owned by the platform layer.
**Status:** Complete and verified — `make health` exits 0 with all components green.
**Dependencies:** Docker Engine 24+, Docker Compose v2, GNU Make.

| Item | Status | Confirmed by |
|---|---|---|
| `docker-compose.yml` | **Existing** | Parsed and executed; 17 containers running |
| `.env.example` | **Existing** | Visible in IDE screenshot |
| `.env` | **Existing** | Visible in IDE screenshot (highlighted); created by `cp .env.example .env` |

---

### `deployment/docker/config/` — **Existing**

**Purpose:** Configuration files mounted read-only into containers at runtime.
**Owner:** Redis Cluster nodes — all 6 mount the same file.
**Status:** Fixed and verified — Redis nodes healthy after inline comments removed.
**Dependencies:** Mounted by docker-compose.yml; must exist before `make up`.

| Item | Status | Confirmed by |
|---|---|---|
| `redis-node.conf` | **Existing** | Visible in screenshot; Redis 6 nodes `(healthy)` in `docker ps` |

---

### `deployment/docker/init-scripts/` — **Existing**

**Purpose:** SQL bootstrap scripts auto-executed by each database container on
first start against an empty volume. Runs once. Flyway takes over after.
**Owner:** Each subdirectory owned by its corresponding database service.
**Status:** All four subdirectories confirmed present. Script execution verified
indirectly — all three Postgres instances returned `accepting connections`.
**Dependencies:** Must exist before `make up`. Executed by Docker entrypoint, not Flyway.

| Subdirectory | Status | Owner service | Confirmed by |
|---|---|---|---|
| `init-scripts/sales-db/` | **Existing** | SaleService | Visible in screenshot |
| `init-scripts/inventory-db/` | **Existing** | InventoryService | Visible in screenshot |
| `init-scripts/orders-db/` | **Existing** | OrderService | Visible in screenshot |
| `init-scripts/clickhouse/` | **Existing** | AnalyticsService | Visible in screenshot |

---

### `deployment/docker/scripts/` — **Existing**

**Purpose:** Operational shell scripts for running against the live Docker stack.
**Owner:** Platform / all engineers.
**Status:** File exists; full execution path not individually verified in terminal
(health check runs via `make health` which calls the Makefile directly).
**Dependencies:** Requires Docker CLI, redis-cli, curl on host machine.

| Item | Status | Confirmed by |
|---|---|---|
| `health-check.sh` | **Existing** | Visible in screenshot |

---

### `deployment/helm/` — **Planned**

**Purpose:** Helm charts for deploying all 5 services to Kubernetes. One chart per
service with HPA, ConfigMap, and Secret templates.
**Owner:** Platform / SRE.
**Status:** Not started. No `.yaml` files written.
**Dependencies:** Week 10. Requires all 5 services fully working.
**Target:** `deployment/helm/charts/{sale,inventory,order,notification,analytics}-service/`

---

### `deployment/terraform/` — **Planned**

**Purpose:** Infrastructure-as-code for AWS (EKS, RDS ×3, ElastiCache, MSK).
**Owner:** Platform / SRE.
**Status:** Not started. No `.tf` files written.
**Dependencies:** Week 10. Requires Helm charts complete.

---

## `docs/` — **Existing (folder only)**

**Purpose:** All written knowledge — architecture, ADRs, API contracts, runbooks.
**Owner:** Varies by subdirectory (see below).
**Status:** Folder confirmed visible in IDE screenshot. Internal structure and file
placement are **unconfirmed** — documents were generated in this session and
downloaded, but whether they were committed to the repository is unknown.
**Dependencies:** None. Read-only reference material.

| Subdirectory | Status | Contents |
|---|---|---|
| `docs/architecture/` | **Planned** | Final-Spec-Council.md, DomainModel.md, DatabaseSchema.md, KafkaDesign.md, RedisDesign.md, PRD, Build-Plan, schema.sql |
| `docs/adr/` | **Planned** | 01-Decisions.md (15 ADRs) |
| `docs/api/` | **Planned** | openapi.yaml |
| `docs/runbooks/` | **Planned** | local-development.md, monitoring-guide.md |

---

## `services/` — **Planned**

**Purpose:** One subdirectory per bounded context. No shared code between services.
**Owner:** Each service owned independently.
**Status:** Directory does not exist. Zero Java code written.
**Dependencies:** Requires `deployment/docker/` working (Week 1 ✅). Starts Week 2.

| Subdirectory | Status | Starts |
|---|---|---|
| `services/sale-service/` | **Planned** | Week 2 |
| `services/inventory-service/` | **Planned** | Week 3 |
| `services/order-service/` | **Planned** | Week 5 |
| `services/notification-service/` | **Planned** | Week 8 |
| `services/analytics-service/` | **Planned** | Week 9 |

---

## `testing/` — **Planned**

**Purpose:** Cross-service tests that span more than one bounded context.
Single-service unit and integration tests live inside each `services/` subdirectory.
**Owner:** All teams.
**Status:** Directory does not exist. No test code written.
**Dependencies:** Requires at least two services running (Week 6+).

| Subdirectory | Status | Purpose |
|---|---|---|
| `testing/contract/` | **Planned** | ArchUnit boundary enforcement |
| `testing/chaos/` | **Planned** | Kafka kill test, Redis failure test |
| `testing/e2e/` | **Planned** | Full stack end-to-end |
| `testing/fixtures/` | **Planned** | Shared Testcontainers setup |

---

## `benchmarks/` — **Planned**

**Purpose:** Performance measurement. Separate from `testing/` — benchmarks
measure throughput and latency, tests verify correctness.
**Owner:** All teams.
**Status:** Directory does not exist. No simulation files written.
**Dependencies:** Requires all 5 services complete (Week 10).

| Subdirectory | Status | Purpose |
|---|---|---|
| `benchmarks/gatling/simulations/` | **Planned** | 50k user load test |
| `benchmarks/gatling/results/` | **Planned** | Generated HTML reports (gitignored) |
| `benchmarks/reports/` | **Planned** | Committed markdown baseline summaries |

---

## `incidents/` — **Planned**

**Purpose:** Operational runbooks, playbooks, and postmortems.
**Owner:** SRE + service teams.
**Status:** Directory does not exist in repository. PM-001 was generated in this
session but placement is unconfirmed.

| Subdirectory | Status | Contents |
|---|---|---|
| `incidents/postmortems/` | **Planned** | PM-001-Week01-Infrastructure.md |
| `incidents/runbooks/` | **Planned** | redis-failure.md, kafka-dlq-depth.md |
| `incidents/playbooks/` | **Planned** | pre-sale-checklist.md |

---

## Summary

| Directory | Status | Services that depend on it |
|---|---|---|
| `deployment/docker/` | **Existing — verified working** | All 5 services (infra layer) |
| `deployment/docker/config/` | **Existing — verified working** | Redis Cluster |
| `deployment/docker/init-scripts/` | **Existing — verified working** | Postgres ×3, ClickHouse |
| `deployment/docker/scripts/` | **Existing** | All engineers |
| `docs/` | **Existing — contents unconfirmed** | Reference only |
| `deployment/helm/` | **Planned** | All 5 services (Week 10) |
| `deployment/terraform/` | **Planned** | AWS infrastructure (Week 10) |
| `services/` | **Planned** | — |
| `testing/` | **Planned** | All 5 services |
| `benchmarks/` | **Planned** | All 5 services |
| `incidents/` | **Planned** | Platform ops |

**Existing directories: 5**
**Planned directories: 16**
**Java services written: 0 of 5**