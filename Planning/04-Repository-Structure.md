# RepoStructure.md
## Flash Sale Platform — Repository Structure
**Version:** 1.0 | **Status:** Final
**Date:** 2026-06-15
**Source:** Final-Spec-Council.md v2.0 · DomainModel.md · Build-Plan.md

> **Rule:** Every directory has a reason. Every file has an owner.
> This document is the source of truth for where things live and why.

---

## Table of Contents

1. [Repository overview](#1-repository-overview)
2. [services/](#2-services)
3. [docs/](#3-docs)
4. [deployment/](#4-deployment)
5. [benchmarks/](#5-benchmarks)
6. [incidents/](#6-incidents)
7. [testing/](#7-testing)
8. [Root files](#8-root-files)
9. [Ownership map](#9-ownership-map)
10. [File placement rules](#10-file-placement-rules)

---

## 1. Repository overview

```
flash-sale-platform/
├── services/               # 5 bounded-context services — one directory per service
├── docs/                   # All written knowledge: architecture, ADRs, API, runbooks
├── deployment/             # Everything needed to run: Docker, Helm, Terraform, scripts
├── benchmarks/             # Load tests, JMH microbenchmarks, performance reports
├── incidents/              # Runbooks, playbooks, postmortems — living operational docs
├── testing/                # Cross-service tests: integration, chaos, e2e, contract
├── README.md               # Entry point — quick start + service index
├── build.gradle            # Root Gradle multi-project build
├── settings.gradle         # Subproject declarations
└── .gitignore              # Secrets, build output, IDE files excluded
```

**Monorepo rationale:** All five services live in one repository:
- Atomic commits across service boundaries (schema change + consumer change in one PR)
- Shared build tooling without a separate registry
- Single PR surface for cross-cutting changes (e.g., Kafka topic rename)

Trade-off: CI pipelines must be service-scoped. Gradle `--project-dir` and path-based
CI filters handle this — a change to `inventory-service` must not deploy `notification-service`.

---

## 2. services/

One directory per bounded context. Services share no code — no shared library module.
If two services need the same type, each defines its own. The ACL translator handles mapping.

```
services/
├── sale-service/
├── inventory-service/
├── order-service/
├── notification-service/
└── analytics-service/
```

### Per-service layout (identical across all 5)

```
{service-name}/
├── Dockerfile                              # JRE 21 Alpine, ZGC, virtual threads flag
├── build.gradle                            # Service-specific Spring Boot dependencies
├── .env.example                            # Template — copy to .env, never commit .env
├── k8s/                                    # Service-specific K8s manifests (non-Helm)
│
└── src/
    ├── main/
    │   ├── java/com/flashsale/{service}/
    │   │   ├── domain/                     # Pure domain — no Spring, no JPA, no framework
    │   │   │   ├── aggregate/              # Aggregate roots (FlashSale, Product, Reservation, Order)
    │   │   │   ├── entity/                 # Child entities (SaleSchedule, StockLevel, OutboxEvent)
    │   │   │   ├── vo/                     # Value objects — Java 21 records, immutable
    │   │   │   ├── event/                  # Domain events (SaleStarted, StockReserved)
    │   │   │   └── port/                   # Outbound ports — interfaces only, no implementations
    │   │   │
    │   │   ├── application/                # Use cases: orchestrate domain + call ports
    │   │   │   ├── {Entity}CommandService  # Write side: PlaceOrder, CreateReservation
    │   │   │   └── {Entity}QueryService    # Read side: GetSaleById, IsSaleActive
    │   │   │
    │   │   ├── infra/                      # Port implementations: Spring, JPA, Redis, Kafka
    │   │   │   ├── db/                     # JPA repositories, Flyway, Postgres adapters
    │   │   │   ├── kafka/                  # Producers, consumers, outbox poller, ACL translators
    │   │   │   └── redis/                  # RedisTemplate usage, Lua script execution
    │   │   │
    │   │   ├── api/                        # HTTP layer only — controllers and DTOs
    │   │   │   ├── controller/             # @RestController
    │   │   │   └── dto/                    # Request/response records
    │   │   │
    │   │   └── config/                     # Spring @Configuration (KafkaConfig, RedisConfig)
    │   │
    │   └── resources/
    │       ├── application.yml             # Config — no secrets, use env vars
    │       ├── lua/                        # Lua scripts loaded at startup via ClassPathResource
    │       └── db/migration/               # Flyway: V1__init.sql, V2__add_index.sql ...
    │
    └── test/
        ├── java/com/flashsale/{service}/
        │   ├── unit/                       # No Spring context, no I/O — milliseconds
        │   └── integration/                # Testcontainers: real Postgres, Redis, Kafka
        └── resources/                      # application-test.yml, test fixtures
```

### Domain layer hard constraint

Enforced by ArchUnit in `testing/contract/ArchitectureBoundaryTest.java`:

```
domain/ MUST NOT depend on:
  infra/    — no JPA annotations, no Redis, no Kafka
  api/      — no HTTP types, no Jackson annotations
  Spring    — no @Component, @Autowired, @Transactional

domain/ MAY use:
  java.*    — standard library only
  port/     — interfaces defined within domain/ itself
```

This is not a convention — it is a failing test.

### Lua scripts

All Lua scripts live in `src/main/resources/lua/` of the owning service.
Loaded once at startup and SHA-cached by Spring.

| Script | Service | Purpose |
|---|---|---|
| `stock_decrement.lua` | inventory-service | Atomic check-and-decrement |
| `stock_prewarm.lua` | inventory-service | Idempotent pre-warm before sale start |
| `stock_release.lua` | inventory-service | Stock restore on expiry or saga compensation |
| `stock_reconcile.lua` | inventory-service | Correct Redis drift from Postgres |
| `rate_limit.lua` | sale-service | Sliding window rate limiter (Sorted Set) |

### Service ports

| Service | Port | Owns |
|---|---|---|
| sale-service | 8081 | FlashSale lifecycle, scheduling, sale:active cache |
| inventory-service | 8082 | Product, Reservation, stock counter, Lua scripts |
| order-service | 8083 | Order, OutboxEvent, IdempotencyRecord, saga |
| notification-service | 8084 | Stateless Kafka consumer, email/push/SMS dispatch |
| analytics-service | 8085 | ClickHouse writer, SaleEventProjection |
| Kafka UI | 8080 | Topic browser (development only) |

---

## 3. docs/

All written knowledge lives here. A PR that changes an architectural decision
must include the corresponding `docs/adr/` update.

```
docs/
├── architecture/           # Design documents — source of truth, council-approved
│   ├── Final-Spec-Council.md       # Service boundaries, Kafka, Redis — binding
│   ├── DomainModel.md              # Aggregates, entities, value objects, bounded contexts
│   ├── DatabaseSchema.md           # Tables, indexes, constraints, query patterns
│   ├── KafkaDesign.md              # Topics, partitions, consumer groups, retry, DLQ
│   ├── RedisDesign.md              # Key structure, TTL, Lua scripts, fallback strategy
│   ├── PRD-FlashSalePlatform.md    # Product requirements, user stories, NFRs
│   └── Build-Plan.md               # 10-week implementation roadmap
│
├── adr/                    # One file per architectural decision
│   ├── ADR-001-lua-atomic-decrement.md
│   ├── ADR-002-virtual-threads.md
│   ├── ADR-003-kafka-async.md
│   ├── ADR-004-transactional-outbox.md
│   ├── ADR-005-retired-3-service-model.md    # Superseded by ADR-009
│   ├── ADR-006-partition-key-saleId.md        # Superseded by ADR-013
│   ├── ADR-007-redis-aof-persistence.md
│   ├── ADR-008-database-per-service.md
│   ├── ADR-009-5-service-final-count.md
│   ├── ADR-010-sale-service-split.md
│   ├── ADR-011-clickhouse-analytics.md
│   ├── ADR-012-choreography-saga.md
│   └── ADR-013-inventory-events-productid-key.md
│
├── api/
│   └── openapi.yaml                # OpenAPI 3.0 spec: all 5 services, all endpoints
│
├── runbooks/               # Normal operations (not incident response)
│   ├── local-development.md
│   ├── deploying-a-sale.md
│   └── monitoring-guide.md
│
└── postmortems/
    └── TEMPLATE.md
```

### ADR format

```markdown
# ADR-NNN — [title]
**Status:** Proposed | Approved | Superseded by ADR-NNN
**Date:** YYYY-MM-DD
**Deciders:** [who decided]

## Context
## Decision
## Alternatives rejected
## Consequences
## Implementation
```

Superseded ADRs are never deleted. Status is updated to `Superseded by ADR-NNN`
and a link added. This preserves the record of why decisions changed over time.

---

## 4. deployment/

Everything needed to run the platform in any environment.

```
deployment/
├── docker/
│   └── docker-compose.yml          # Local: Postgres x3, Redis Cluster, Kafka KRaft, ClickHouse
│
├── helm/
│   ├── charts/
│   │   ├── sale-service/
│   │   │   ├── Chart.yaml
│   │   │   ├── values.yaml         # Default values (non-sensitive)
│   │   │   └── templates/
│   │   │       ├── deployment.yaml
│   │   │       ├── service.yaml
│   │   │       ├── hpa.yaml        # HorizontalPodAutoscaler
│   │   │       ├── configmap.yaml
│   │   │       └── secret.yaml     # Sealed Secrets reference — no plaintext
│   │   ├── inventory-service/      # Max replicas: 10 (matches Kafka partition count)
│   │   ├── order-service/          # Max replicas: 10
│   │   ├── notification-service/   # Max replicas: 5
│   │   └── analytics-service/      # Max replicas: 5
│   │
│   └── environments/
│       ├── local/
│       │   └── values.yaml         # 1 replica, debug logging, no TLS
│       ├── staging/
│       │   └── values.yaml         # 2 replicas, managed infra
│       └── production/
│           └── values.yaml         # Full HPA, PDBs, resource limits, mTLS
│
├── terraform/
│   ├── modules/
│   │   ├── eks/                    # EKS cluster
│   │   ├── rds/                    # RDS PostgreSQL multi-AZ (3 instances)
│   │   ├── elasticache/            # ElastiCache Redis Cluster mode
│   │   └── msk/                    # Amazon MSK
│   │
│   └── environments/
│       ├── staging/
│       │   └── terraform.tfvars    # GITIGNORED
│       └── production/
│           └── terraform.tfvars    # GITIGNORED
│
└── scripts/
    ├── health-check.sh             # Verify all infra healthy: Postgres, Redis, Kafka
    └── pre-sale.sh                 # T-30 checklist: pre-scale pods, verify pre-warm
```

### HPA by service

| Service | Min | Max | CPU trigger | Note |
|---|---|---|---|---|
| sale-service | 2 | 8 | 70% | Read-heavy; Redis absorbs spike |
| inventory-service | 2 | 10 | 70% | Pre-scale to 10 before sale start |
| order-service | 2 | 10 | 70% | Pre-scale to 10 before sale start |
| notification-service | 1 | 5 | 70% | Async; lag tolerable |
| analytics-service | 1 | 5 | 70% | Async; 5s lag is acceptable |

---

## 5. benchmarks/

Performance measurement. Separate from `testing/` — benchmarks measure characteristics,
tests verify correctness.

```
benchmarks/
├── gatling/
│   ├── simulations/
│   │   └── FlashSaleSimulation.scala   # 50k users, reservation load test
│   └── results/                        # HTML reports (gitignored — too large)
│
├── k6/
│   └── reservation-spike.js            # Spike test: 0 → 50k in 1 second
│
├── jmh/
│   └── LuaScriptBenchmark.java         # Lua vs WATCH/MULTI/EXEC throughput comparison
│
└── reports/
    └── YYYY-MM-DD-baseline.md          # Committed markdown summary per release
```

### Pass criteria (from AC-001, AC-002)

```
p99 reservation latency  < 50ms
Oversell count           = 0
Duplicate order count    = 0
```

Baseline results are committed to `benchmarks/reports/` as markdown. Full Gatling
HTML is stored as a CI artefact, not in git.

---

## 6. incidents/

Operational knowledge — what to do when things go wrong. Unlike `docs/` (design),
this is operational practice that evolves with every incident.

```
incidents/
├── runbooks/               # Fix guides — one per failure mode
│   ├── redis-failure.md            # Redis cluster down
│   ├── kafka-dlq-depth.md          # DLQ depth exceeded, replay procedure
│   ├── postgres-slow-query.md      # Slow query degrading reservation latency
│   ├── oversell-detected.md        # EMERGENCY: confirmed orders > totalStock
│   └── kafka-partition-lag.md      # Consumer lag > 10k messages
│
├── playbooks/              # Ordered procedures for known scenarios
│   ├── pre-sale-checklist.md       # T-30 min → T-0 before every sale
│   ├── post-sale-reconciliation.md # Verify Redis/Postgres consistency
│   └── rolling-deploy-during-sale.md
│
├── postmortems/            # Written after every P0/P1
│   └── TEMPLATE.md
│
└── templates/
    └── RUNBOOK_TEMPLATE.md
```

### Required runbook structure

```
1. Symptoms          — alert text, user impact, anomalous metrics
2. Immediate actions — numbered steps, first 5 minutes
3. Diagnosis         — specific commands and what output means
4. Mitigation        — fix commands in sequence
5. Recovery check    — how to confirm resolution
6. Post-incident     — postmortem link, follow-up items
```

### Alert → runbook index

| Alert condition | Runbook |
|---|---|
| `redis_connection_errors_total > 5 in 60s` | `incidents/runbooks/redis-failure.md` |
| `notifications_dlq_depth > 100` | `incidents/runbooks/kafka-dlq-depth.md` |
| `reservation_latency_p99 > 250ms` | `incidents/runbooks/postgres-slow-query.md` |
| `kafka_consumer_lag > 10000` | `incidents/runbooks/kafka-partition-lag.md` |
| `confirmed_orders > sale_total_stock` | `incidents/runbooks/oversell-detected.md` |

---

## 7. testing/

Cross-service and system-level tests. Single-service tests live in `services/{svc}/src/test/`.

```
testing/
├── integration/
│   └── ReservationSagaIT.java          # Full saga across 3 services
│
├── contract/
│   └── ArchitectureBoundaryTest.java   # ArchUnit: no inventory.* in order.*
│
├── chaos/
│   ├── KafkaFailureTest.java           # Kafka killed mid-sale → 0 lost events
│   ├── RedisFailureTest.java           # Redis down → Postgres fallback, 0 oversells
│   └── PodCrashTest.java               # Pod killed mid-transaction → idempotency holds
│
├── e2e/
│   └── FlashSaleE2ETest.java           # Create sale → reserve → order → confirm
│
├── load/
│   └── SustainedLoadTest.java          # 30 min at 10k RPS (manual trigger only)
│
└── fixtures/
    ├── SaleFixtures.java
    ├── ReservationFixtures.java
    └── OrderFixtures.java
```

### Test taxonomy

| Type | Location | Runs in CI | Typical runtime |
|---|---|---|---|
| Unit | `services/{svc}/src/test/unit/` | Every commit | < 5s per service |
| Integration | `services/{svc}/src/test/integration/` | Every PR | < 2 min per service |
| Contract (ArchUnit) | `testing/contract/` | Every PR | < 30s |
| Multi-service integration | `testing/integration/` | Every PR | < 5 min |
| Chaos | `testing/chaos/` | Weekly + pre-release | 10–30 min |
| E2E | `testing/e2e/` | Pre-release | 5–10 min |
| Load | `testing/load/` + `benchmarks/` | Manual pre-release | 30–60 min |

---

## 8. Root files

| File | Purpose |
|---|---|
| `README.md` | Entry point: quick start, service table, documentation index |
| `build.gradle` | Root build: Java 21 toolchain, shared test deps, virtual thread defaults |
| `settings.gradle` | `include 'services:inventory-service'` etc. |
| `.gitignore` | Excludes `.env`, `*.tfstate`, `target/`, `.idea/`, `application-local.yml` |

### What does not live at the root

- No application code
- No shared library module
- No `docker-compose.yml` — lives in `deployment/docker/`
- No Kubernetes manifests — lives in `deployment/helm/` or `services/{svc}/k8s/`

---

## 9. Ownership map

| Directory | Owner | Access rule |
|---|---|---|
| `services/sale-service/` | SaleService team | SaleService team only |
| `services/inventory-service/` | InventoryService team | InventoryService team only |
| `services/order-service/` | OrderService team | OrderService team only |
| `services/notification-service/` | NotificationService team | NotificationService team only |
| `services/analytics-service/` | AnalyticsService team | AnalyticsService team only |
| `docs/architecture/` | Staff Engineer Council | Council approval required |
| `docs/adr/` | Proposing team | Author proposes, council approves |
| `docs/api/` | Each service team | Must match controller signatures |
| `deployment/helm/templates/` | Platform / SRE | SRE approval required |
| `deployment/helm/values.yaml` | Any team | Open — values, not templates |
| `deployment/terraform/` | Platform / SRE | SRE only |
| `deployment/scripts/` | Platform / SRE | SRE only |
| `benchmarks/` | Any team | Open |
| `incidents/runbooks/` | SRE + service teams | SRE approval required |
| `incidents/playbooks/` | SRE | SRE only |
| `incidents/postmortems/` | Incident owner | Written by EM post-incident |
| `testing/contract/` | Staff Engineer | Council approval (boundary rules) |
| `testing/chaos/` | SRE + service teams | SRE approval required |
| `testing/fixtures/` | Any team | Open |

---

## 10. File placement rules

Ten rules. Apply in order — first match wins.

**Rule 1 — Service code belongs to the service.**
If a file is part of a service's implementation, it lives in `services/{service-name}/`.
No exceptions.

**Rule 2 — Shared code does not exist.**
If you are about to create a `shared/` or `common/` module, stop. Each service owns
its own types. The ACL translator handles cross-service mapping. Shared code is
the first step toward coupling.

**Rule 3 — Lua scripts live next to the service that owns them.**
`services/inventory-service/src/main/resources/lua/stock_decrement.lua` —
not in `deployment/` or a root `scripts/` folder.

**Rule 4 — Architecture decisions live in `docs/adr/`.**
If a decision changes how the system is built → ADR.
If it changes how the system is operated → `incidents/runbooks/`.

**Rule 5 — Deployment artefacts live in `deployment/`.**
Docker Compose, Helm charts, Terraform, and operational scripts live here —
not at the root and not inside service directories.

**Rule 6 — Cross-service tests live in `testing/`.**
Single-service tests: `services/{service}/src/test/`.
Tests requiring two or more services: `testing/integration/`, `testing/e2e/`,
or `testing/chaos/`.

**Rule 7 — Performance measurement lives in `benchmarks/`.**
Gatling simulations, k6 scripts, and JMH benchmarks are not correctness tests.
They live in `benchmarks/`, not `testing/`.

**Rule 8 — Operational procedures live in `incidents/`.**
How to fix a Redis outage is operational knowledge, not architecture.
It lives in `incidents/runbooks/`, not `docs/architecture/`.

**Rule 9 — Secrets never live in this repository.**
`.env`, `terraform.tfvars`, `application-secrets.yml`, database passwords,
Redis auth tokens — all gitignored. Use Kubernetes Sealed Secrets or a secrets
manager. The `.gitignore` enforces this; a pre-commit hook should too.

**Rule 10 — Place files where their reader will look first.**
A developer building InventoryService: `services/inventory-service/`.
An SRE responding to a Redis alert: `incidents/runbooks/`.
A new engineer understanding the architecture: `docs/architecture/`.
When in doubt, ask: who needs this file, and where will they look?

---

*Repository structure derived from Final-Spec-Council.md v2.0, DomainModel.md, and Build-Plan.md.*
*Reflects 5 bounded contexts, database-per-service, hexagonal architecture, and zero shared modules.*
*The domain layer constraint is enforced at test time by ArchUnit in testing/contract/.*