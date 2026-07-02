# Docker.md
## Flash Sale Platform — Docker Reference
**Audience:** Interview preparation
**Source:** `deployment/docker/docker-compose.yml` — verified against running stack

---

## Table of Contents

1. [Core concepts](#1-core-concepts)
2. [Your stack at a glance](#2-your-stack-at-a-glance)
3. [Images](#3-images)
4. [Containers](#4-containers)
5. [Volumes](#5-volumes)
6. [Networks](#6-networks)
7. [Key commands](#7-key-commands)
8. [Interview questions](#8-interview-questions)

---

## 1. Core Concepts

### Image vs Container

| Concept | Java analogy | Your file |
|---|---|---|
| **Image** | Compiled `.jar` — read-only, portable, shareable | `postgres:16.3-alpine`, `apache/kafka:3.7.0`, ... |
| **Container** | Running JVM process from that `.jar` | `flash-sale-sales-db`, `flash-sale-kafka`, ... |
| **`docker compose up`** | `java -jar app.jar` — but for infrastructure | Starts 14 containers from 6 images |

An image is a snapshot. A container is a live process. One image can spawn many containers — `postgres:16.3-alpine` is downloaded once and creates three completely independent Postgres instances, each with its own memory and filesystem.

### Volume

A container's internal filesystem is **ephemeral** — it disappears when the container is removed. Volumes are Docker-managed storage that lives outside the container and survives restarts.

```yaml
# docker-compose.yml — sales-db
volumes:
  - sales-db-data:/var/lib/postgresql/data        # named volume (persists)
  - ./init-scripts/sales-db:/docker-entrypoint-initdb.d:ro  # bind mount (reads from host)
```

Two types in your compose file:

| Type | Syntax | Lives | Survives `make clean`? |
|---|---|---|---|
| Named volume | `sales-db-data:/path` | Docker-managed | No (`-v` flag removes it) |
| Bind mount | `./local/path:/container/path` | On your laptop | Yes (it's just a folder) |

### Network

Containers are network-isolated by default. Your compose file creates one bridge network — `flash-sale-net` — and places all 14 containers on it. Inside this network, **container names become hostnames**. Docker's internal DNS resolves them.

```yaml
# kafka-ui connects to Kafka using the container name, not localhost
KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
```

Port mappings (`"host:container"`) punch a hole from your laptop into the network:

```
Your laptop:9092  →  flash-sale-net  →  kafka container:9092
Your laptop:5432  →  flash-sale-net  →  sales-db container:5432
```

---

## 2. Your Stack at a Glance

```
flash-sale-platform (name: from compose file)
│
├── flash-sale-net (172.28.0.0/16, bridge)
│   │
│   ├── PostgreSQL × 3 (postgres:16.3-alpine)
│   │   ├── flash-sale-sales-db       → sales_db     :5432
│   │   ├── flash-sale-inventory-db   → inventory_db :5433
│   │   └── flash-sale-orders-db      → orders_db    :5434
│   │
│   ├── Redis Cluster × 7 (redis:7.2.5-alpine)
│   │   ├── flash-sale-redis-1..6     → cluster nodes :7001–7006
│   │   └── flash-sale-redis-cluster-init  → one-shot bootstrap
│   │
│   ├── flash-sale-kafka              → apache/kafka:3.7.0     :9092
│   ├── flash-sale-clickhouse         → clickhouse-server:24.3.3  :8123/:19000
│   ├── flash-sale-kafka-ui           → kafka-ui:v0.7.2         :18080
│   └── flash-sale-redisinsight       → redisinsight:2.50       :18081
│
└── Named volumes (12)
    ├── sales-db-data, inventory-db-data, orders-db-data
    ├── redis-node-1-data .. redis-node-6-data
    ├── kafka-data
    └── clickhouse-data, clickhouse-logs
```

---

## 3. Images

Six distinct images. Each is downloaded once and reused across containers.

| Image | Registry | Containers created |
|---|---|---|
| `postgres:16.3-alpine` | hub.docker.com | sales-db, inventory-db, orders-db |
| `redis:7.2.5-alpine` | hub.docker.com | redis-node-1..6, redis-cluster-init |
| `apache/kafka:3.7.0` | hub.docker.com | kafka |
| `clickhouse/clickhouse-server:24.3.3-alpine` | hub.docker.com | clickhouse |
| `provectuslabs/kafka-ui:v0.7.2` | hub.docker.com | kafka-ui |
| `redislabs/redisinsight:2.50` | hub.docker.com | redisinsight |

**`alpine` suffix** means the image is built on Alpine Linux — a minimal ~5 MB base OS. Full images use Ubuntu/Debian and are 200–400 MB larger. Alpine reduces attack surface and download time. Trade-off: some glibc tools are absent; must use `apk` not `apt`.

**YAML anchors** (`&` and `*`) avoid repetition for shared settings:

```yaml
x-postgres-common: &postgres-common  # defines the anchor
  image: postgres:16.3-alpine
  restart: unless-stopped
  networks: [flash-sale-net]
  healthcheck: ...

sales-db:
  <<: *postgres-common               # merges anchor, then overrides below
  environment:
    POSTGRES_DB: sales_db
```

---

## 4. Containers

### PostgreSQL — 3 containers

**Why three, not one?** Architecture enforces zero cross-service joins at the infrastructure level. A slow query on `orders_db` cannot steal I/O from `inventory_db`. Each schema can evolve independently. An `inventory_db` incident pages only the Inventory team.

```yaml
inventory-db:
  ports:
    - "5433:5432"    # host 5433 → container 5432 (container always thinks it's on 5432)
  command:
    - postgres
    - -c
    - lock_timeout=5000    # inventory-db only — caps SELECT FOR UPDATE wait
                           # when Redis circuit breaker opens under high load
```

`POSTGRES_INITDB_ARGS: "--data-checksums"` — detects storage corruption at block level. Enabled once at volume creation; cannot be changed without reinitialising.

`shared_preload_libraries=pg_stat_statements` — tracks query-level execution stats. Required by NFR-024 for slow query monitoring. Set via `-c` flag in the command list.

`log_min_duration_statement=100` — logs any query exceeding 100ms. Critical for identifying index misses before they become incidents.

---

### Redis — 7 containers

**Why 6 nodes?** Redis Cluster requires a minimum of 3 primaries. Each primary gets 1 replica for high availability. 3+3=6.

```yaml
redis-node-1:
  command: >
    redis-server /usr/local/etc/redis/redis.conf
    --requirepass ${REDIS_PASSWORD:-redis_dev}
    --cluster-announce-hostname redis-node-1   # Docker service name as hostname
    --cluster-announce-port 6379
    --cluster-announce-bus-port 16379
```

`cluster-announce-hostname redis-node-1` is the critical setting. Without it, nodes advertise their container-internal IP address (e.g. `172.28.0.4`). If the container restarts, the IP changes. Other nodes lose track of it. Using the service name (`redis-node-1`) — resolved by Docker DNS — is stable across restarts.

**`redis-cluster-init` — one-shot bootstrap container:**

```yaml
redis-cluster-init:
  restart: "no"          # do NOT auto-restart on failure
  depends_on:
    redis-node-1: { condition: service_healthy }
    # ... all 6 nodes must be healthy before this runs
```

`restart: "no"` — Docker daemon does not auto-restart on crash. But `docker compose up` WILL recreate a stopped container on every run. The idempotency guard inside the command handles this:

```sh
CLUSTER_STATE=$$(redis-cli ... CLUSTER INFO | grep cluster_state | cut -d: -f2)
if [ "$$CLUSTER_STATE" = "ok" ]; then
  exit 0    # cluster already exists — skip creation
fi
redis-cli --cluster create ...  # only runs on first boot
```

`$$` in compose YAML: compose substitutes `$` as variable expansion. `$$` escapes to a literal `$` for the shell. Without `$$`, compose tries to expand `$CLUSTER_STATE` as a compose variable and finds nothing, resulting in empty string.

---

### Kafka — 1 container

```yaml
kafka:
  image: apache/kafka:3.7.0
  environment:
    KAFKA_PROCESS_ROLES: broker,controller   # KRaft: one process, both roles
    KAFKA_NODE_ID: 1
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093

    KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
    KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092

    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

**KRaft mode** — no Zookeeper. The broker and controller are the same process. In the old model, Zookeeper was a separate cluster you had to operate and monitor. KRaft eliminates it.

**Two listeners, one broker:**

| Listener | Address | Used by |
|---|---|---|
| `INTERNAL` | `kafka:29092` | Containers inside `flash-sale-net` (e.g. kafka-ui, future app services) |
| `EXTERNAL` | `localhost:9092` | Your laptop — IDE, tests, `kafka-topics.sh` on host |
| `CONTROLLER` | `kafka:9093` | Internal KRaft controller communication only |

Why two? Inside Docker, `localhost` refers to the container itself, not your laptop. A service inside Docker must use `kafka:29092`. Your laptop must use `localhost:9092`. Same Kafka broker, two different access paths.

**`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`** — application services own their topics via `@Bean KafkaTopicConfig`. A typo in a topic name fails loudly instead of silently creating a phantom topic with wrong partition count.

---

### ClickHouse — 1 container

```yaml
clickhouse:
  image: clickhouse/clickhouse-server:24.3.3-alpine
  ports:
    - "8123:8123"    # HTTP interface — JDBC driver uses this
    - "19000:9000"   # Native TCP — remapped because host port 9000 was in use
  ulimits:
    nofile:
      soft: 262144
      hard: 262144
```

**Why not a fourth Postgres?** Postgres is row-oriented: `SELECT COUNT(*) GROUP BY event_type` reads every column in every row to count one column. ClickHouse is column-oriented: it reads only the `event_type` column. For analytics over millions of rows, this is 10–100x faster. Running such queries on the same Postgres instance as OLTP would saturate shared I/O.

**`ulimits: nofile: 262144`** — ClickHouse opens one file per column per shard. The default Linux file descriptor limit (1024) is hit immediately. This override sets the limit per-container without affecting the host OS.

**Port 19000, not 9000** — host port 9000 was already in use on the development machine. The container still listens on 9000 internally (`19000:9000`). Only the host-side mapping changed.

---

### Kafka UI and RedisInsight — dev tools only

Neither is part of your application. Both are never deployed.

```yaml
kafka-ui:
  depends_on:
    kafka: { condition: service_healthy }   # waits for Kafka healthcheck to pass
  environment:
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092   # internal listener
```

**Kafka UI** (`localhost:18080`) — browse topics, see partition counts, inspect message payloads, watch consumer group lag per partition. Critical for debugging: when a consumer is falling behind, this shows which partition is lagging and by how many messages.

**RedisInsight** (`localhost:18081`) — browse Redis keys by pattern, watch `stock:{saleId}` count down in real time during load tests, inspect TTLs, run Lua scripts interactively.

---

## 5. Volumes

12 named volumes. Managed entirely by Docker. Deleted only with `docker compose down -v`.

| Volume | Container | What is stored |
|---|---|---|
| `sales-db-data` | flash-sale-sales-db | flash_sales, sale_schedules tables |
| `inventory-db-data` | flash-sale-inventory-db | products, stock_levels, reservations |
| `orders-db-data` | flash-sale-orders-db | orders, order_outbox, idempotency_keys |
| `redis-node-1-data` .. `redis-node-6-data` | each Redis node | AOF log + cluster topology (`nodes.conf`) |
| `kafka-data` | flash-sale-kafka | Topic logs, KRaft metadata, consumer offsets |
| `clickhouse-data` | flash-sale-clickhouse | MergeTree column files, indices |
| `clickhouse-logs` | flash-sale-clickhouse | Server logs (separate volume to allow independent cleanup) |

**Bind mounts** (not volumes — read from your laptop at runtime):

```yaml
- ./init-scripts/sales-db:/docker-entrypoint-initdb.d:ro
- ./config/redis-node.conf:/usr/local/etc/redis/redis.conf:ro
```

The `:ro` flag makes the bind mount read-only inside the container. The container can read the config file but cannot modify it.

**`PGDATA: /var/lib/postgresql/data/pgdata`** — Postgres data is written to a subdirectory (`pgdata/`) inside the volume mount point. Without this, the Postgres image writes a `lost+found` directory at the volume root that conflicts with the init scripts directory. The subdirectory separates them cleanly.

---

## 6. Networks

```yaml
networks:
  flash-sale-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.28.0.0/16
```

**Bridge network** — Docker creates a virtual switch. Containers attached to it can communicate with each other by service name. Your laptop is not on the network; port mappings (`"host:container"`) are the bridge between your laptop and the network.

**`172.28.0.0/16`** — chosen to avoid conflict with Docker's default bridge (`172.17.0.0/16`) and common VPN ranges (`10.0.0.0/8`, `192.168.0.0/16`). The `/16` provides 65,534 addresses — more than enough for development.

**DNS resolution inside the network:**

```
flash-sale-kafka-ui container:
  KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:29092
  ↓
  Docker DNS resolves "kafka" to 172.28.x.x (kafka container's IP)
  ↓
  Connects to kafka container on port 29092
```

This is why `localhost` cannot be used inside containers for inter-service communication. `localhost` inside a container refers to the container itself, not the host machine or other containers.

---

## 7. Key Commands

```bash
# Start all infrastructure (detached)
make up
# equivalent to:
docker compose -f deployment/docker/docker-compose.yml --env-file deployment/docker/.env up -d

# Stop (keep volumes — data survives)
make down

# Stop + destroy all data (volumes deleted)
make clean
# equivalent to: docker compose down -v --remove-orphans

# Check status
make ps

# Tail logs
make logs
docker logs flash-sale-kafka --follow --tail=50

# Enter a Postgres shell
make db-inventory
# equivalent to: docker exec -it flash-sale-inventory-db psql -U flashsale -d inventory_db

# Check Kafka topics
make kafka-topics

# Check Redis cluster state
make redis-cluster-info
```

**`restart: unless-stopped`** — on all persistent services. If a container crashes, Docker restarts it automatically. If you explicitly `docker stop` it, it stays stopped. This means `make down` stops everything cleanly; individual crashes auto-recover.

**`condition: service_healthy`** in `depends_on` — Compose waits for a container's healthcheck to return exit code 0 before starting the dependent container. Without this, Kafka UI might start before Kafka is ready and fail to connect.

---

## 8. Interview Questions

**"What is the difference between a Docker image and a container?"**
An image is a read-only filesystem snapshot — like a compiled `.jar`. A container is a live process created from that image — like a running JVM. Multiple containers can be created from the same image. Containers share the image but have isolated filesystems, memory, and network interfaces.

**"Why do you have three Postgres containers instead of one?"**
Architecture enforces zero cross-service joins at the infrastructure level. A slow query on `orders_db` cannot saturate shared I/O and degrade `inventory_db` latency. Incident scoping is unambiguous — a `sales_db` alert pages only the Sale team. Each schema can evolve independently without coordinating migrations across services.

**"What happens to your data when you run `make clean`?"**
`make clean` runs `docker compose down -v`. The `-v` flag tells Docker to delete all named volumes — `sales-db-data`, `inventory-db-data`, all Redis AOF logs, Kafka topic data, everything. The next `make up` starts completely fresh. Without `-v` (just `make down`), volumes survive and all data is preserved.

**"How does `kafka-ui` connect to Kafka when it's in a separate container?"**
`kafka-ui` uses `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092`. Both containers are on `flash-sale-net`. Docker's internal DNS resolves the hostname `kafka` to the Kafka container's IP address. From inside the network, `kafka:29092` is the internal listener. From your laptop, Kafka is reached at `localhost:9092` — a different listener on the same broker.

**"Why does `redis-cluster-init` have `restart: \"no\"` and what does that actually mean?"**
`restart: "no"` means the Docker daemon will not auto-restart the container if it exits. But it does NOT prevent `docker compose up` from recreating a stopped container on subsequent runs. Without the idempotency guard in the command (`CLUSTER INFO` check), `--cluster create` would run on every `make up` and fail with `[ERR] Node is not empty`. The guard exits 0 if `cluster_state:ok` and only creates the cluster on first boot.

**"What is KRaft and why does your Kafka use it?"**
KRaft is Kafka without Zookeeper. Before KRaft, Kafka required a separate Zookeeper cluster (3+ nodes minimum) to store metadata and manage leader elections. This doubled the operational burden. KRaft moves metadata management into Kafka itself — the broker and controller run in the same process. `KAFKA_PROCESS_ROLES: broker,controller` tells this single container to handle both roles.

**"What does `$$` mean in your YAML cluster-init command?"**
Docker Compose performs variable substitution on `$VAR` patterns. `$$` is the escape sequence for a literal dollar sign — Compose converts `$$` to `$` and passes it to the shell. The `redis-cluster-init` command uses shell variables (`$CLUSTER_STATE`) that must not be expanded by Compose. Writing `$$CLUSTER_STATE` in the YAML means Compose substitutes `$$` → `$`, and the shell sees `$CLUSTER_STATE` — a valid variable reference.

**"Why is ClickHouse in your stack instead of a fourth Postgres?"**
ClickHouse is column-oriented; Postgres is row-oriented. Analytics queries (`GROUP BY`, aggregations, time-series) over millions of events scan one column at a time in ClickHouse. Postgres reads every column in every matching row. For analytical workloads at flash sale event volumes, ClickHouse is 10–100× faster. Running heavy aggregation queries on the same Postgres instance as OLTP would saturate shared I/O and degrade reservation latency.

---

*Source: `deployment/docker/docker-compose.yml` (verified running stack — `make health` exits 0).*
*ADR-008 (database-per-service), ADR-016 (Redis cluster topology), ADR-017 (Kafka KRaft), ADR-018 (ClickHouse).*