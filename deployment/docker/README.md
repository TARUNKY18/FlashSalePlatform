# Infrastructure README
## Flash Sale Platform — Local Development Stack

Welcome to the project. This document is everything you need to get the local
infrastructure running, understand what is running and why, and diagnose problems
when things go wrong.

> **Read this before running anything.** The stack has 17 containers. Understanding
> what they are before starting saves significant debugging time.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [What is running](#2-what-is-running)
3. [Service ports at a glance](#3-service-ports-at-a-glance)
4. [First-time setup](#4-first-time-setup)
5. [Starting the stack](#5-starting-the-stack)
6. [Stopping the stack](#6-stopping-the-stack)
7. [Resetting the stack](#7-resetting-the-stack)
8. [Health check commands](#8-health-check-commands)
9. [Connecting to databases](#9-connecting-to-databases)
10. [Kafka operations](#10-kafka-operations)
11. [Redis operations](#11-redis-operations)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Prerequisites

Install these before anything else.

| Tool | Min version | Check | Install |
|---|---|---|---|
| Docker Engine | 24.0+ | `docker --version` | https://docs.docker.com/get-docker |
| Docker Compose | v2 plugin | `docker compose version` | Included with Docker Desktop |
| GNU Make | 3.81+ | `make --version` | `brew install make` / `apt install make` |
| redis-cli | 7.x | `redis-cli --version` | `brew install redis` / `apt install redis-tools` |
| psql | 16.x | `psql --version` | `brew install libpq` / `apt install postgresql-client` |
| curl | any | `curl --version` | Pre-installed on most systems |

> **Note:** Use `docker compose` (v2, space) not `docker-compose` (v1, hyphen).
> Run `docker compose version` to confirm. You need `v2.x`.

**Disk space:** First `make up` pulls ~3 GB of images. Subsequent starts are instant.

**Memory:** Docker Desktop needs at least 6 GB RAM allocated. Set in
Docker Desktop → Settings → Resources → Memory.

---

## 2. What is running

The stack has five categories of service. Understanding why each exists
helps when you need to diagnose a problem.

### PostgreSQL — 3 separate databases

Three completely isolated Postgres instances. This is deliberate — the architecture
requires that no service can query another service's database. Separate instances
enforce this at the infrastructure level, not just by convention.

| Container | Database | What stores it |
|---|---|---|
| `flash-sale-sales-db` | `sales_db` | Flash sale lifecycle, schedules, status history |
| `flash-sale-inventory-db` | `inventory_db` | Products, stock levels, reservations |
| `flash-sale-orders-db` | `orders_db` | Orders, outbox events, idempotency keys |

If SaleService is slow, look at `sales-db`. If OrderService fails, look at
`orders-db`. They cannot affect each other.

### Redis Cluster — 6 nodes

Redis runs as a 6-node cluster: 3 primary shards and 1 replica each. In production
this provides high availability. In development it ensures the application code is
always tested against a real cluster — not a standalone Redis instance that behaves
differently in important ways (key routing, CLUSTER commands, multi-key operations).

The cluster is bootstrapped by a 7th container (`flash-sale-redis-cluster-init`)
that runs once, forms the cluster, and exits. On subsequent starts it detects the
existing cluster and exits cleanly. Seeing it in `Exited (0)` state is correct.

You interact with the cluster through any of the 6 nodes — use node 1 (port 7001)
as your entry point for diagnostic commands.

### Kafka — single broker

One Kafka broker in KRaft mode (no Zookeeper). Single broker is correct for local
development — production uses 3 brokers with replication.

Kafka has two listener addresses:
- `kafka:29092` — used by services running inside Docker
- `localhost:9092` — used by you, your IDE, and tools on your laptop

### ClickHouse — analytics database

ClickHouse stores event data for the analytics service. It is a columnar database
optimised for aggregation queries — not the same as Postgres and not a replacement
for it. The `flash_sale` database and `sale_events` table are pre-created on first start.

### Tooling UIs

Two browser-based UIs for inspecting the infrastructure — never deployed to production:
- **Kafka UI** at http://localhost:18080 — browse topics, see consumer group lag
- **RedisInsight** at http://localhost:18081 — browse Redis keys, watch stock counters

---

## 3. Service ports at a glance

### Databases

| Service | Host port | What connects |
|---|---|---|
| `sales-db` | **5432** | SaleService, `make db-sales`, psql |
| `inventory-db` | **5433** | InventoryService, `make db-inventory`, psql |
| `orders-db` | **5434** | OrderService, `make db-orders`, psql |
| ClickHouse HTTP | **8123** | AnalyticsService JDBC, curl health checks |
| ClickHouse TCP | **9000** | ClickHouse client tools |

### Redis Cluster nodes

All 6 nodes accept commands. Use node 1 (7001) for diagnostics.
Pass `-c` to `redis-cli` to enable cluster-aware routing.

| Node | Host port | Cluster bus |
|---|---|---|
| redis-node-1 | **7001** | 17001 |
| redis-node-2 | **7002** | 17002 |
| redis-node-3 | **7003** | 17003 |
| redis-node-4 | **7004** | 17004 |
| redis-node-5 | **7005** | 17005 |
| redis-node-6 | **7006** | 17006 |

### Kafka

| Listener | Address | Used by |
|---|---|---|
| External | **localhost:9092** | Your IDE, kafka-topics.sh, local tests |
| Internal | `kafka:29092` | Application services inside Docker |
| KRaft controller | `localhost:9093` | Kafka internal only — do not use directly |

### UIs

| Tool | URL |
|---|---|
| Kafka UI | http://localhost:18080 |
| RedisInsight | http://localhost:18081 |

### Check for port conflicts before starting

```bash
lsof -i :5432 -i :5433 -i :5434 -i :9092 -i :8123 -i :7001 2>/dev/null | grep LISTEN
```

If anything is listed, stop the conflicting process before running `make up`.

---

## 4. First-time setup

Run this once when you clone the repository.

```bash
# 1. Copy the environment template
#    .env is gitignored — it cannot be accidentally committed
cd deployment/docker
cp .env.example .env

# 2. Inspect it (optional — defaults work for local dev)
cat .env
```

The `.env` file contains passwords (`flashsale_dev`, `redis_dev`) for local
development only. They are intentionally weak. Do not reuse them anywhere else.

---

## 5. Starting the stack

All `make` commands run from the **project root** — the directory containing
`services/`, `deployment/`, etc. Not from inside `deployment/docker/`.

### Start

```bash
make up
```

This command starts all 17 containers in dependency order, waits for healthchecks
to pass, and automatically runs `make health`.

**Expected duration:** 60–90 seconds on first run (image pulls).
20–30 seconds on all subsequent runs.

**Expected output at the end of `make up`:**

```
=== PostgreSQL ===
  ✓ sales_db
  ✓ inventory_db
  ✓ orders_db

=== Redis Cluster ===
  cluster_state:ok

=== Kafka ===
  ✓ Kafka broker reachable

=== ClickHouse ===
  ✓ ClickHouse HTTP

=== UIs ===
  ✓ Kafka UI (http://localhost:18080)

✓ Health check complete
```

If any line shows `✗`, see [Troubleshooting](#12-troubleshooting).

### Checking container status

```bash
make ps
```

All containers should show `healthy` or `running`. One exception:
`flash-sale-redis-cluster-init` will show `Exited (0)`. **This is correct** —
it ran once to form the cluster and exited successfully.

### Viewing logs

```bash
make logs                                   # tail all services
docker logs flash-sale-kafka --follow       # single service
docker logs flash-sale-inventory-db --tail=50
```

---

## 6. Stopping the stack

### Stop (keep your data)

```bash
make down
```

Stops all containers. Volumes are preserved — databases, Kafka topics, and Redis
data survive. The next `make up` starts in seconds and picks up where you left off.

Use this at the end of the day or when switching branches.

### Pause without stopping

To free up memory temporarily without losing container state:

```bash
docker compose -f deployment/docker/docker-compose.yml pause

# Resume later
docker compose -f deployment/docker/docker-compose.yml unpause
```

---

## 7. Resetting the stack

Use these when you need a completely fresh environment.

### Soft reset — wipe data, keep images

```bash
make clean
```

Stops all containers and **deletes all volumes**. Everything is gone:
- All Postgres data
- All Redis keys
- All Kafka topics and messages
- ClickHouse tables

The next `make up` rebuilds from scratch including the Redis cluster and Postgres
schemas via init scripts. Takes ~60 seconds.

> **Warning:** `make clean` destroys all local data. There is no undo.

### Hard reset — wipe everything including images

```bash
make nuke
```

Same as `make clean` but also removes Docker images. Use only if you suspect a
corrupt image layer. The next `make up` re-downloads ~3 GB of images.

> **Warning:** `make nuke` uses `--rmi all` which removes all locally-built Docker
> images, not just flash-sale ones. Run `docker images` first to see what is affected.

### Reset a single service

If only one database is corrupted and you want to avoid resetting everything:

```bash
# Example: reset inventory-db only
docker compose -f deployment/docker/docker-compose.yml stop inventory-db
docker volume rm flash-sale-platform_inventory-db-data
docker compose -f deployment/docker/docker-compose.yml up -d inventory-db
```

Replace `inventory-db` and `inventory-db-data` with the target service/volume.

---

## 8. Health check commands

### Full health check

```bash
make health
```

Checks all five infrastructure components. Run this:
- After `make up` (automatic, but re-run if you are unsure)
- After your laptop resumes from sleep
- Before running integration tests
- When a service cannot connect to its dependency

### Individual checks

**PostgreSQL — all three:**
```bash
docker exec flash-sale-sales-db     pg_isready -U flashsale -d sales_db
docker exec flash-sale-inventory-db pg_isready -U flashsale -d inventory_db
docker exec flash-sale-orders-db    pg_isready -U flashsale -d orders_db
# Expected: "accepting connections"
```

**Redis Cluster state:**
```bash
make redis-cluster-info
# or:
redis-cli -p 7001 -a redis_dev --no-auth-warning CLUSTER INFO
# Must show: cluster_state:ok  cluster_known_nodes:6  cluster_size:3
```

**Redis all 6 nodes:**
```bash
make redis-check
# Expected: "Node 7001: PONG" through "Node 7006: PONG"
```

**Kafka broker:**
```bash
docker exec flash-sale-kafka \
  kafka-broker-api-versions.sh --bootstrap-server localhost:9092
# Expected: clean output, no ERROR lines
```

**ClickHouse:**
```bash
curl http://localhost:8123/ping
# Expected: Ok.
```

**After laptop sleep — always check Redis:**
```bash
# Redis cluster gossip times out during hibernation
make redis-cluster-info | grep cluster_state
# If not "cluster_state:ok": see troubleshooting section
```

---

## 9. Connecting to databases

### PostgreSQL shell

```bash
make db-sales        # psql → sales_db
make db-inventory    # psql → inventory_db
make db-orders       # psql → orders_db
```

Useful psql commands once inside:

```sql
\dt                  -- list tables
\d table_name        -- describe a table (columns, indexes, constraints)
\l                   -- list databases
SHOW lock_timeout;   -- should be 5000ms on inventory-db, 0 on the others
SHOW timezone;       -- should be UTC on all three
\q                   -- quit
```

### PostgreSQL from an IDE or DB tool

| Database | Host | Port | User | Password | DB name |
|---|---|---|---|---|---|
| sales_db | localhost | 5432 | flashsale | flashsale_dev | sales_db |
| inventory_db | localhost | 5433 | flashsale | flashsale_dev | inventory_db |
| orders_db | localhost | 5434 | flashsale | flashsale_dev | orders_db |

### ClickHouse shell

```bash
docker exec -it flash-sale-clickhouse \
  clickhouse-client --user flashsale --password flashsale_dev
```

```sql
SHOW DATABASES;
USE flash_sale;
SHOW TABLES;          -- should show: sale_events, sale_metrics
DESCRIBE TABLE sale_events;
```

### ClickHouse HTTP API

```bash
curl "http://localhost:8123/?user=flashsale&password=flashsale_dev" \
  --data "SELECT COUNT(*) FROM flash_sale.sale_events"
```

---

## 10. Kafka operations

### Create topics manually

Topics are normally created by services on startup. If you need them without
starting services:

```bash
make kafka-create-topics
```

This creates:

| Topic | Partitions | Purpose |
|---|---|---|
| `sale-events` | 8 | Sale lifecycle events |
| `inventory-events` | 16 | Stock reservations (double partitions — highest throughput) |
| `order-events` | 8 | Order lifecycle events |
| `notifications.dlq` | 4 | Failed notification deliveries |
| `analytics.dlq` | 4 | Failed analytics ingestion |

### List topics

```bash
make kafka-topics
```

### Check consumer group lag

Lag = unprocessed messages. Should be 0 under normal conditions.

```bash
make kafka-lag
# Look at the LAG column. High lag = consumer is behind.
```

### Browse messages for debugging

```bash
# Read last 5 messages from a topic
docker exec flash-sale-kafka \
  kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic inventory-events \
  --from-beginning \
  --max-messages 5 \
  --property print.key=true
```

### Kafka UI

Open http://localhost:18080. The UI shows topics, partition counts, message
counts, consumer groups, and their per-partition lag. You can also browse
individual message payloads — useful for debugging event schemas.

---

## 11. Redis operations

### Watch a stock counter drain

```bash
# Replace <uuid> with the actual sale ID
make redis-stock-watch SALE_ID=<uuid>
# or directly:
watch -n 1 "redis-cli -p 7001 -a redis_dev --no-auth-warning -c GET 'stock:<uuid>'"
```

### Inspect any key

The `-c` flag routes your command to the correct shard automatically.

```bash
# Get a key value
redis-cli -p 7001 -a redis_dev --no-auth-warning -c GET "stock:some-uuid"

# Check TTL remaining on a key (seconds, -2 = key doesn't exist, -1 = no TTL)
redis-cli -p 7001 -a redis_dev --no-auth-warning -c TTL "stock:some-uuid"

# Check if a sale is active
redis-cli -p 7001 -a redis_dev --no-auth-warning -c GET "sale:active:some-uuid"

# Check rate limit entries for a user in a sale
redis-cli -p 7001 -a redis_dev --no-auth-warning -c ZCARD "rate:user-uuid:sale-uuid"
```

### Cluster diagnostics

```bash
# Full cluster state
make redis-cluster-info

# Which nodes are primary / replica
redis-cli -p 7001 -a redis_dev --no-auth-warning CLUSTER NODES

# Memory usage across all nodes
for port in 7001 7002 7003 7004 7005 7006; do
  echo -n "Node $port: "
  redis-cli -p $port -a redis_dev --no-auth-warning INFO memory \
    | grep used_memory_human
done
```

### RedisInsight

Open http://localhost:18081. Add a connection:
- Host: `localhost` · Port: `7001` · Password: `redis_dev`

Use RedisInsight to visually browse the key namespace and watch counters update
in real time during load tests.

---

## 12. Troubleshooting

Work through these from top to bottom. Each section gives the symptom, the cause,
and the exact fix.

---

### `make up` hangs for more than 3 minutes

**Cause:** Images downloading on a slow connection, or Docker out of memory.

```bash
# Watch what is happening
docker compose -f deployment/docker/docker-compose.yml pull

# Increase Docker Desktop memory: Settings → Resources → Memory → 6 GB+
```

---

### A Postgres container shows `Exited (1)`

**Cause A — Port conflict.** Something is already on port 5432/5433/5434.
```bash
lsof -i :5432 -i :5433 -i :5434
# Stop the conflicting process, then:
make down && make up
```

**Cause B — Corrupt volume.** Usually shows "database files are incompatible"
in the logs.
```bash
docker logs flash-sale-sales-db 2>&1 | tail -20
# If you see "incompatible" or "invalid page":
docker compose -f deployment/docker/docker-compose.yml stop sales-db
docker volume rm flash-sale-platform_sales-db-data
docker compose -f deployment/docker/docker-compose.yml up -d sales-db
```

---

### `flash-sale-redis-cluster-init` shows `Exited (0)` — is this normal?

**Yes. This is correct.** The init container runs once to form the cluster
and exits successfully. `Exited (0)` means it finished without error.
`Exited (1)` would indicate a problem.

---

### Redis cluster shows `cluster_state:fail`

**Most common cause:** Laptop hibernation — nodes disconnect from each other
during sleep and the cluster enters a failed state on wake.

**Quick fix:**
```bash
docker compose -f deployment/docker/docker-compose.yml restart \
  redis-node-1 redis-node-2 redis-node-3 \
  redis-node-4 redis-node-5 redis-node-6
sleep 15 && make redis-cluster-info | grep cluster_state
```

**If restart does not fix it:**
```bash
# Reset all Redis nodes (Redis data is lost, but Redis is never the source
# of truth — Postgres still has everything)
docker compose -f deployment/docker/docker-compose.yml stop \
  redis-node-1 redis-node-2 redis-node-3 \
  redis-node-4 redis-node-5 redis-node-6 redis-cluster-init

for i in 1 2 3 4 5 6; do
  docker volume rm flash-sale-platform_redis-node-$i-data
done

docker compose -f deployment/docker/docker-compose.yml up -d \
  redis-node-1 redis-node-2 redis-node-3 \
  redis-node-4 redis-node-5 redis-node-6
sleep 20
docker compose -f deployment/docker/docker-compose.yml up redis-cluster-init
```

---

### Kafka shows `Exited (1)` or connection times out

**Diagnosis:**
```bash
docker logs flash-sale-kafka 2>&1 | tail -30
```

**Cause A — "Inconsistent cluster ID":** Volume has stale metadata from a
previous Kafka instance.
```bash
docker compose -f deployment/docker/docker-compose.yml stop kafka
docker volume rm flash-sale-platform_kafka-data
docker compose -f deployment/docker/docker-compose.yml up -d kafka
sleep 30 && make kafka-topics
```

**Cause B — Port 9092 in use:**
```bash
lsof -i :9092
# Stop the conflicting process, then restart Kafka:
docker compose -f deployment/docker/docker-compose.yml restart kafka
```

---

### ClickHouse health check fails

**Cause:** ClickHouse takes 20–30 seconds to start — longer than other services.
Running `make health` immediately after `make up` may catch it mid-startup.

```bash
# Wait and retry
sleep 30 && curl http://localhost:8123/ping
# Expected: Ok.

# If still failing after 60 seconds:
docker logs flash-sale-clickhouse 2>&1 | tail -30
```

---

### Spring service cannot connect to Postgres / Redis / Kafka

This happens when running services from your IDE against the Docker infrastructure.

**Postgres:** Services running on your laptop must use `localhost`, not the
container name. Container names only resolve inside Docker.
```yaml
# application.yml
spring.datasource.url=jdbc:postgresql://localhost:5433/inventory_db
```

**Redis:** List all 6 nodes using localhost host-ports:
```yaml
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
```

**Kafka:** Use the external listener:
```yaml
spring.kafka.bootstrap-servers=localhost:9092
```
Services inside Docker use `kafka:29092`. Everything outside Docker uses `localhost:9092`.

---

### `make health` fails every time after laptop sleep

```bash
# Restart everything cleanly
make restart

# Then check Redis specifically — it is the most sensitive to sleep/wake cycles
make redis-cluster-info | grep cluster_state
```

---

### Docker ran out of disk space

```bash
# See how much space Docker is using
docker system df

# Remove unused images, stopped containers, unused networks (safe)
docker system prune

# If you need a full clean (warning: removes ALL stopped containers/unused images)
docker system prune -a
```

---

### Nothing obvious is wrong but a service keeps restarting

```bash
# Check if a container is being OOM-killed (killed for using too much memory)
docker inspect flash-sale-kafka | grep -A3 OOMKilled
# If "OOMKilled": true — increase Docker Desktop memory allocation

# Check resource usage in real time
docker stats
```

---

## Quick reference

```
make up                          start everything
make down                        stop everything, keep data
make restart                     stop + start
make health                      full health check
make ps                          container status
make logs                        tail all logs
make clean                       STOP + DELETE ALL DATA  ⚠
make nuke                        STOP + DELETE DATA + IMAGES  ⚠

make db-sales                    psql → sales_db (port 5432)
make db-inventory                psql → inventory_db (port 5433)
make db-orders                   psql → orders_db (port 5434)

make kafka-topics                list Kafka topics
make kafka-lag                   consumer group lag
make kafka-create-topics         create topics manually

make redis-cluster-info          Redis cluster state
make redis-check                 ping all 6 nodes
make redis-stock-watch SALE_ID=  watch stock counter live

make help                        all available targets
```

---

*If something is broken and not covered here, check `incidents/runbooks/` for
service-specific failure guides. If you discover a new failure mode, add it here
before you close your laptop — future you will thank you.*