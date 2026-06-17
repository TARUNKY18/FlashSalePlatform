# CURRENT_STATE.md
**Milestone:** Week 1 — Infrastructure Foundation
**Status:** ✅ COMPLETE
**Date:** 2026-06-17
**Engineer:** Tarun K Y

---

## Completed Work

- `docker-compose.yml` — 17 containers: Postgres ×3, Redis Cluster ×6, Kafka KRaft,
  ClickHouse, Kafka UI, RedisInsight
- `Makefile` — at project root; `make up / down / clean / health` all verified
- `redis-node.conf` — cluster mode, AOF everysec, allkeys-lru, keyspace events Ex
- `init-scripts` — Postgres ×3 (extensions, UTC, grants) + ClickHouse (sale_events table)
- `health-check.sh` — validates all 5 infrastructure components
- `.env.example` — all environment variables documented

**Bugs found and fixed before deployment (see PM-001):**

| ID | Fix |
|---|---|
| M1 | redis-cluster-init not idempotent — rewritten as YAML list + `\|` block |
| M3 | log_line_prefix spaces broke Postgres argv — YAML double-quoted |
| M4 | bitnami/kafka:3.7.0 tag removed from registry — migrated to apache/kafka:3.7.0 |
| M5 | KAFKA_CFG_* not recognised by apache/kafka — renamed to KAFKA_* |
| M6 | redis-node.conf inline comments rejected by Redis 7.2.5 parser — moved to own lines |
| M7 | ClickHouse port 9000 in use on host — remapped to 19000 |

---

## Running Services

| Container | Image | Port | Health |
|---|---|---|---|
| flash-sale-sales-db | postgres:16.3-alpine | 5432 | ✅ healthy |
| flash-sale-inventory-db | postgres:16.3-alpine | 5433 | ✅ healthy |
| flash-sale-orders-db | postgres:16.3-alpine | 5434 | ✅ healthy |
| flash-sale-redis-1..6 | redis:7.2.5-alpine | 7001–7006 | ✅ healthy |
| flash-sale-kafka | apache/kafka:3.7.0 | 9092 | ✅ healthy |
| flash-sale-clickhouse | clickhouse-server:24.3.3-alpine | 8123 / 19000 | ✅ healthy |
| flash-sale-kafka-ui | kafka-ui:v0.7.2 | 18080 | ✅ healthy |
| flash-sale-redisinsight | redisinsight:2.50 | 18081 | ✅ running |

---

## Validation Evidence

```
make health output (run twice — both identical):

=== PostgreSQL ===
  ✓ sales_db   ✓ inventory_db   ✓ orders_db

=== Redis Cluster ===
  cluster_state:ok

=== Kafka ===
  ✓ Kafka broker reachable

=== ClickHouse ===
  Ok.   ✓ ClickHouse HTTP

=== UIs ===
  ✓ Kafka UI (http://localhost:18080)

✓ Health check complete
```

`make up` idempotency confirmed — second run shows Redis cluster skipping
creation (`cluster_state:ok`) and all services remain healthy.

---

## Open Issues

| ID | Description | Priority | Target |
|---|---|---|---|
| P7 | `lua-time-limit 5000ms` too high in redis-node.conf | Medium | Before Week 3 |
| S2 | All ports bind `0.0.0.0` — should be `127.0.0.1` | Low | Week 2 cleanup |
| M14 | `sleep 5` in Makefile too short for cold starts | Low | Week 2 cleanup |

---

## Current Branch

```
main  (single branch — no branching strategy implemented yet)
```

---

## Next Milestone

**Week 2 — SaleService Skeleton**

- Spring Boot 3 project with Java 21 virtual threads
- `FlashSale` aggregate with sealed interface `SaleStatus`
- Flyway V1 migration for `sales_db`
- `POST /api/v1/sales` and `GET /api/v1/sales/{id}`
- Unit tests: 8 state machine transitions
- **Done when:** `make health` still exits 0 with SaleService running alongside infra