# Redis.md
## Flash Sale Platform — Redis Reference
**Audience:** Interview preparation
**Source:** `deployment/docker/docker-compose.yml` · `deployment/docker/config/redis-node.conf`
         · `services/inventory-service/src/main/resources/lua/stock_decrement.lua`
**Verified:** `make health` exits 0 — `cluster_state:ok`

---

## Table of Contents

1. [Why Redis, not PostgreSQL](#1-why-redis-not-postgresql)
2. [The race condition](#2-the-race-condition)
3. [The Lua script](#3-the-lua-script)
4. [Redis Cluster](#4-redis-cluster)
5. [Your six nodes](#5-your-six-nodes)
6. [Configuration reference](#6-configuration-reference)
7. [Key design](#7-key-design)
8. [Failure modes](#8-failure-modes)
9. [Interview questions](#9-interview-questions)

---

## 1. Why Redis, not PostgreSQL

### The problem

During a flash sale, 50,000 users hit Buy Now in the same second against a product
with 1,000 units. Every request must read the stock count and decrement it if stock
is available. This must happen in under 50ms at P99.

### Why Postgres cannot be the hot path

A Postgres `SELECT FOR UPDATE` acquires a row-level lock:

```sql
BEGIN;
SELECT stock FROM stock_levels WHERE sale_id = ? FOR UPDATE;
-- check, then:
UPDATE stock_levels SET stock = stock - 1 WHERE sale_id = ?;
COMMIT;
```

While one transaction holds that lock, 49,999 others are queued. Your connection
pool has a ceiling — say 100 connections. The other 49,900 requests cannot even
begin a transaction and time out. Under this load, Postgres P99 latency is measured
in seconds, not milliseconds.

This is why `inventory-db` has `lock_timeout=5000` in the compose file. It is a
**safety valve for the fallback path only**, not a normal operating condition:

```yaml
# docker-compose.yml — inventory-db only
- lock_timeout=5000    # caps SELECT FOR UPDATE wait at 5s during Redis failure
```

### Why Redis can be the hot path

Two properties make Redis suitable:

**Entirely in-memory.** `GET stock:{saleId}` is a hash table lookup. No disk read.
Sub-millisecond latency at any throughput level the hardware can deliver.

**Single-threaded command execution.** Redis executes one command at a time on a
single thread. This serialisation is the foundation of atomicity — no two commands
interleave, which is what makes the Lua script work.

The mental model: Redis is a `ConcurrentHashMap` in its own process, in RAM,
with a single thread processing every operation sequentially.

### The contract

| Role | Component | Why |
|---|---|---|
| Source of truth | PostgreSQL `inventory_db` | ACID guarantees, durable, queryable |
| Hot-path gate | Redis `stock:{saleId}` | In-memory, atomic via Lua, 50ms P99 |
| Fallback | Postgres `SELECT FOR UPDATE` | When Redis returns -2 (cache miss) |

**Redis is never the source of truth.** It is a gate. Postgres has the authoritative
stock record at all times.

---

## 2. The Race Condition

This is the bug that Redis + Lua prevents. Without atomicity:

```
Thread A:  GET stock:{saleId}  →  reads 1
Thread B:  GET stock:{saleId}  →  reads 1   (simultaneously)

Thread A:  stock > 0, proceed
Thread B:  stock > 0, proceed               (both passed the check)

Thread A:  DECR stock:{saleId}  →  stock = 0   (sold to A)
Thread B:  DECR stock:{saleId}  →  stock = -1  (OVERSOLD — two units sold, one existed)
```

This is a TOCTOU bug — Time Of Check To Time Of Use. There is a window between
the `GET` and the `DECR` where another thread can observe the same state and
proceed. Two separate Redis commands are not atomic — anything can run between them.

### Why `WATCH`/`MULTI`/`EXEC` is not the answer

Redis offers optimistic locking via `WATCH`. If a watched key changes between
`WATCH` and `EXEC`, the transaction is aborted and the client must retry. Under
50,000 concurrent requests against one key, most transactions abort and retry —
creating a retry storm that makes the problem worse, not better.

### Why Lua is the answer

A Lua script executes entirely on the Redis thread before any other command runs.
There is no window. The `GET` and `DECRBY` inside the script are one indivisible
operation from the perspective of any other client.

---

## 3. The Lua Script

`stock_decrement.lua` — the core correctness guarantee of the entire platform:

```lua
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to reserve (integer >= 1)
-- Returns : -2 = cache miss | -1 = sold out | >= 0 = remaining stock

local stock = tonumber(redis.call('GET', KEYS[1]))

if stock == nil then
    return -2               -- key does not exist → cache miss → Postgres fallback
end

if stock <= 0 then
    return -1               -- zero or negative → sold out → HTTP 409
end

local qty = tonumber(ARGV[1]) or 1

if stock < qty then
    return -1               -- not enough for requested quantity → sold out
end

redis.call('DECRBY', KEYS[1], qty)
return stock - qty          -- remaining stock after decrement
```

### Return code handling in InventoryService

| Return | Meaning | Action |
|---|---|---|
| `-2` | Cache miss — key not in Redis | `SELECT stock FOR UPDATE` on Postgres; re-warm Redis |
| `-1` | Sold out | Return HTTP `409 SOLD_OUT` immediately |
| `>= 0` | Success — remaining stock | Write reservation row; publish `StockReserved` to Kafka |

### The floor guarantee

`if stock <= 0 then return -1` is the oversell prevention. Even if somehow stock
reaches 0 while a script is about to run, the script checks before decrementing.
The check and decrement execute as one unit — the floor is enforced atomically.
Stock can never go below zero via the Lua path.

### `stock_prewarm.lua` — idempotent pre-warm

Called by SaleService 60 seconds before sale start:

```lua
-- KEYS[1] : stock:{saleId}
-- KEYS[2] : stock:warmed:{saleId}   ← completion marker (same shard via hash tag)
-- ARGV[1] : totalStock
-- ARGV[2] : TTL seconds

local already = redis.call('GET', KEYS[2])
if already then return 0 end          -- another pod already warmed this sale

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])   -- set stock counter
redis.call('SET', KEYS[2], '1',     'EX', ARGV[2])   -- mark as warmed

return 1
```

The `stock:warmed:{saleId}` marker prevents multiple InventoryService pods from
all racing to pre-warm the same sale simultaneously. Uses a hash tag (`{saleId}`)
to force both keys onto the same shard — required for a multi-key Lua script in
a cluster.

---

## 4. Redis Cluster

### Why a single node is insufficient

| Problem | Impact |
|---|---|
| Single point of failure | Node crash = no stock counter = all reservations fall back to Postgres = collapse |
| Throughput ceiling | ~100–200k ops/sec per node; approaches limit at 50k concurrent |
| Memory cap | 4 GB cap = limited key space for many concurrent sales + session data |

### How cluster mode distributes data

Redis Cluster uses **hash slots**. There are exactly 16,384 slots. Every key is
assigned to a slot by `CRC16(key) % 16384`. Each primary node owns a range of slots.

```
stock:{abc-uuid}
  → hash the content between { } = "abc-uuid"
  → CRC16("abc-uuid") % 16384 = 7432
  → slot 7432 → node-2 handles this key
```

Clients (Lettuce in your Spring services) compute the slot locally and route
directly to the correct node. No broadcast, no coordination — one network hop.

### Hash tags — why `{saleId}` is in your key names

`stock:{saleId}` and `stock:warmed:{saleId}` must land on the same shard to be
used together in a Lua script. Redis Cluster only computes the slot from the
content between the first `{` and `}`. Both keys hash on `saleId`, so both go
to the same node — guaranteed.

Without hash tags, two keys with different full names may land on different shards.
A multi-key Lua script across shards produces a `CROSSSLOT` error.

### Cluster topology from your compose file

```
redis-cli --cluster create
  redis-node-1:6379 redis-node-2:6379 redis-node-3:6379
  redis-node-4:6379 redis-node-5:6379 redis-node-6:6379
  --cluster-replicas 1
```

First 3 nodes listed = primaries. Last 3 = replicas. Redis assigns replica-to-primary
mapping for load balancing.

---

## 5. Your Six Nodes

| Container | Host port | Cluster bus | Role | Hash slots |
|---|---|---|---|---|
| flash-sale-redis-1 | :7001 | :17001 | Primary | 0 – 5460 |
| flash-sale-redis-2 | :7002 | :17002 | Primary | 5461 – 10922 |
| flash-sale-redis-3 | :7003 | :17003 | Primary | 10923 – 16383 |
| flash-sale-redis-4 | :7004 | :17004 | Replica | mirrors node-1 |
| flash-sale-redis-5 | :7005 | :17005 | Replica | mirrors node-2 |
| flash-sale-redis-6 | :7006 | :17006 | Replica | mirrors node-3 |

### Cluster bus ports (the `1700x` mappings)

The `17001–17006` ports carry cluster gossip — not data. Nodes constantly ping each
other on the bus to share topology, detect failures, and coordinate slot migration.
Each node runs two ports: the command port (6379, mapped to `700x` on host) and
the cluster bus port (16379, mapped to `1700x`).

The bus port is always `command_port + 10000`. This is hardcoded in Redis — it
cannot be configured.

### `--cluster-announce-hostname redis-node-N`

Each node passes its Docker service name as the hostname it advertises to other
cluster nodes. Without this, nodes advertise their container-internal IP address
(e.g. `172.28.0.4`). Container IPs change on restart. Service names are stable and
resolved by Docker's internal DNS. This is why your `command:` block in the compose
file includes this flag for every node.

---

## 6. Configuration Reference

Every setting in `deployment/docker/config/redis-node.conf` — what it does and why:

```conf
cluster-enabled yes
```
Enables Redis Cluster mode. Without this, each node is a standalone instance —
they do not communicate or share hash slots.

```conf
cluster-config-file /data/nodes.conf
```
Redis writes the cluster topology to this file. On restart, the node reads it to
rejoin the cluster without needing to rediscover peers. Lives in `/data/`, which
is the named volume mount — survives container restarts.

```conf
cluster-node-timeout 5000
```
How long (ms) a node can be unreachable before the cluster marks it as failed and
promotes its replica. At 5000ms, primary failure recovery takes approximately 5
seconds. Lower = faster failover but more false positives (network blips trigger
unnecessary elections).

```conf
cluster-require-full-coverage no
```
The cluster continues serving requests even when one shard is completely offline
(both primary and replica lost). Keys on the failed shard return errors; keys on
healthy shards work normally. The alternative (`yes`) takes the entire cluster
offline if any shard is lost — catastrophic for a flash sale. `no` is correct.

```conf
appendonly yes
appendfsync everysec
```
AOF (Append-Only File) persistence. Every write is buffered to an in-memory log and
flushed to disk once per second. Maximum data loss on a crash: 1 second of writes.
Redis is never the source of truth, so 1 second of loss is acceptable — Postgres
has the durable record. `always` (flush on every write) is safer but halves write
throughput. `no` (OS-managed flush) risks losing minutes of data.

```conf
maxmemory 256mb
maxmemory-policy allkeys-lru
maxmemory-samples 10
```
Hard memory cap per node. When exceeded, Redis evicts keys using LRU (Least Recently
Used) approximation. `allkeys-lru` evicts any key, not just those with TTLs. This
is correct for the flash sale use case: active sale stock counters are accessed
constantly and are the last to be evicted. `maxmemory-samples 10` samples 10 random
keys per eviction decision — higher accuracy than the default of 5.

```conf
notify-keyspace-events Ex
```
Enables keyspace notifications for expiry events only. When a reservation TTL
expires (`idem:{userId}:{key}`, `session:{userId}`), Redis publishes on a special
pub/sub channel. InventoryService subscribes to release stock automatically when
reservations are abandoned. Without this, expired reservations would require polling.

```conf
lua-time-limit 5000
```
Maximum execution time for a Lua script before Redis raises an error and allows
other commands to run. At 5000ms, a hanging script blocks all Redis commands for
up to 5 seconds — unacceptable at 50k RPS. **This is a known open issue (P7)
in the project.** Should be 500ms or lower before Week 3 Lua integration work begins.

---

## 7. Key Design

Five distinct key namespaces, each with a specific TTL contract:

| Key pattern | Type | TTL | Owner | Purpose |
|---|---|---|---|---|
| `stock:{saleId}` | String (int) | sale_end + 10min | InventoryService | Atomic stock counter — decremented by Lua |
| `stock:warmed:{saleId}` | String | sale_end + 10min | InventoryService | Pre-warm idempotency marker |
| `sale:active:{saleId}` | String | sale_end + 1min | SaleService | Hot-path liveness check — GET returns "1" if active |
| `sale:meta:{saleId}` | Hash | sale_end + 1min | SaleService | Sale metadata (name, startTime, totalStock) |
| `idem:{userId}:{key}` | String | 24h fixed | OrderService | Idempotency key cache (fast path) |
| `rate:{userId}:{window}` | Sorted Set | 60s rolling | API Gateway | Sliding window rate limiter |

Hash tags ensure co-located keys: `stock:{saleId}` and `stock:warmed:{saleId}`
both hash on `saleId` and land on the same shard — required for `stock_prewarm.lua`.

---

## 8. Failure Modes

### Redis node goes down — one primary fails

Replica detects failure after `cluster-node-timeout` (5000ms). Replica promotes
automatically. Slot ownership transfers. The cluster is degraded but operational.
Total impact: ~5 second window where keys on the failed shard return errors.

### Redis cluster `cluster_state:fail`

Entire cluster enters fail state if a shard loses both its primary and replica
simultaneously. `cluster-require-full-coverage no` prevents this from taking the
entire cluster offline — surviving shards continue serving. Failed shard returns
errors. Recover by restarting failed nodes; cluster reforms automatically.

### Redis returns `-2` (cache miss) — stock key not in Redis

Lua script returns `-2`. InventoryService falls back to:
```sql
SELECT stock FROM stock_levels WHERE sale_id = ? FOR UPDATE
```
Postgres handles the reservation. InventoryService re-warms the Redis key on
success. Latency increases significantly (Postgres lock contention). Throughput
drops. `lock_timeout=5000` prevents indefinite blocking.

### Redis memory limit hit — `allkeys-lru` evicts

Redis evicts the least recently used keys when `maxmemory 256mb` is reached.
Active sale stock counters are accessed constantly — they are the last to be
evicted. Old sale keys and expired session keys are evicted first. If a stock key
is evicted mid-sale, the next request returns `-2` and falls back to Postgres,
which re-warms the key.

### After laptop hibernate — `cluster_state:fail`

Redis cluster gossip times out during sleep. Nodes mark each other as failed. On
wake, the cluster may not automatically recover. Fix: restart all 6 nodes.

```bash
docker compose -f deployment/docker/docker-compose.yml restart \
  redis-node-1 redis-node-2 redis-node-3 \
  redis-node-4 redis-node-5 redis-node-6
sleep 15 && make redis-cluster-info | grep cluster_state
```

---

## 9. Interview Questions

**"Why Redis and not Postgres for the stock counter?"**
Postgres `SELECT FOR UPDATE` serialises all concurrent requests through one row lock.
At 50,000 concurrent requests, 49,999 queue behind the lock holder. P99 goes from
milliseconds to seconds. Connection pool exhausts. Redis executes `DECRBY` atomically
via the single-threaded command executor — no locking, no waiting, sub-millisecond
at any throughput level the hardware supports.

**"How do you prevent overselling when two requests arrive simultaneously?"**
A Lua script in Redis. The `GET` (check stock) and `DECRBY` (decrement) execute
as one atomic operation on the Redis thread. No command from any other client can
run between them. There is no window for a race condition. The script returns one
of three codes: `-2` (cache miss), `-1` (sold out), or `≥ 0` (remaining stock).

**"Why not use Redis `WATCH`/`MULTI`/`EXEC` for atomicity?"**
`WATCH` is optimistic locking: if the watched key changes between `WATCH` and `EXEC`,
the transaction aborts and the client retries. Under 50,000 concurrent requests
against one stock counter, almost every transaction aborts. The retry storm makes
contention worse. Lua does not retry — it executes once, atomically.

**"What are the six Redis nodes actually doing?"**
Three are primaries, each owning roughly 5,460 of the 16,384 total hash slots. They
handle all reads and writes. Three are replicas — each mirrors one primary in real
time. Replicas promote to primary automatically within `cluster-node-timeout` (5 seconds)
if their primary fails. The cluster bus ports (`17001–17006`) carry gossip — nodes
continuously ping each other to share topology and detect failures.

**"How does Redis know which node to send a key to?"**
`CRC16(key) % 16384` produces the hash slot. The client (Lettuce) computes this
locally and connects directly to the node that owns that slot. One hop, no
intermediary. For keys with `{saleId}` in the name, only the content inside
the braces is hashed — this is a hash tag, which forces related keys onto the
same shard and enables multi-key Lua scripts.

**"What is `cluster-require-full-coverage no` and why does your config use it?"**
With `yes`, the entire cluster goes offline if any shard loses both its primary
and replica. With `no`, surviving shards continue serving; only keys on the lost
shard return errors. For a flash sale — where availability matters more than
perfect consistency — `no` is correct. A partial outage (some sales unavailable)
is better than a total outage (all sales unavailable).

**"What does `notify-keyspace-events Ex` enable?"**
Expiry-specific keyspace notifications. When a TTL-bearing key expires, Redis
publishes on a pub/sub channel. InventoryService subscribes to release stock when
reservation sessions expire. Without this, discovering expired reservations requires
polling the database — adding latency and coupling.

**"What is `lua-time-limit 5000` and why is it a problem in your config?"**
It caps Lua script execution at 5 seconds before Redis raises an error. At 5000ms,
a hanging script blocks all Redis commands for up to 5 seconds — unacceptable at
50,000 RPS. This is an open issue (P7) in the project. It should be 500ms or lower
before the Lua scripts are integrated into services in Week 3.

**"Redis is not your source of truth — what is?"**
PostgreSQL `inventory_db`. Redis holds a pre-warmed cache of the stock counter for
fast atomic access. If Redis is wiped (`make clean`), the stock values still live in
`stock_levels` in Postgres. InventoryService re-warms Redis from Postgres on a
cache miss. This is why losing Redis data is operationally recoverable.

---

*Source: ADR-001 (Lua atomic decrement), ADR-003 (SELECT FOR UPDATE fallback),*
*ADR-011 (Redis three-layer contract), ADR-016 (Redis Cluster topology).*
*All decisions in `docs/adr/01-Decisions.md`.*