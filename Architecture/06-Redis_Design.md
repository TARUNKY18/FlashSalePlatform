# RedisDesign.md
## Flash Sale Platform — Redis Architecture
**Version:** 1.0 | **Status:** Final
**Date:** 2026-06-15
**Source:** Final-Spec-Council.md v2.0
**Cardinal rule:** Redis is never the source of truth.
                   Every key has a Postgres fallback.
                   A total Redis failure must degrade performance, not corrupt data.

---

## Table of Contents

1. [Cluster Topology](#1-cluster-topology)
2. [Key Structure](#2-key-structure)
3. [TTL Strategy](#3-ttl-strategy)
4. [Layer 1 — Stock Counter + Lua Scripts](#4-layer-1--stock-counter--lua-scripts)
5. [Layer 2 — Rate Limiter](#5-layer-2--rate-limiter)
6. [Layer 3 — Session Cache](#6-layer-3--session-cache)
7. [Layer 4 — Idempotency Cache](#7-layer-4--idempotency-cache)
8. [Layer 5 — Sale Metadata Cache](#8-layer-5--sale-metadata-cache)
9. [Fallback Strategy](#9-fallback-strategy)
10. [Stampede Guard](#10-stampede-guard)
11. [Eviction Policy](#11-eviction-policy)
12. [Spring Boot Implementation](#12-spring-boot-implementation)
13. [Operational Runbook](#13-operational-runbook)

---

## 1. Cluster Topology

```
Redis Cluster — 3 primary shards, 1 replica each

  Shard 0 (slots 0–5460)        Shard 1 (slots 5461–10922)     Shard 2 (slots 10923–16383)
  ┌──────────────────┐           ┌──────────────────┐           ┌──────────────────┐
  │  Primary 0       │           │  Primary 1       │           │  Primary 2       │
  │  Replica 0       │           │  Replica 1       │           │  Replica 2       │
  └──────────────────┘           └──────────────────┘           └──────────────────┘
```

**Configuration:**

| Parameter | Value | Reason |
|---|---|---|
| Mode | Redis Cluster | Horizontal sharding; no single-node bottleneck |
| Shards | 3 primary + 3 replica | High availability; automatic failover |
| Memory cap | 4 GB total (≈1.3 GB/shard) | Eviction kicks in before OOM |
| Eviction policy | `allkeys-lru` | LRU eviction protects hot keys automatically |
| Persistence | AOF `everysec` | ≤1s data loss; Postgres is source of truth |
| Max memory policy | `allkeys-lru` | Evict least-recently-used keys across all keyspaces |
| Notify keyspace events | `Ex` | Expiry events for reservation TTL callbacks |

**Key distribution rule:**
Redis Cluster distributes keys by `CRC16(key) % 16384`. Keys for the same sale that
must be operated atomically (e.g., `stock:{saleId}` and `sale:meta:{saleId}`) are
co-located using hash tags: `{saleId}` — the curly braces force Kafka to hash only
the inner string, placing both keys on the same shard. This is mandatory for any
multi-key Lua script.

---

## 2. Key Structure

### Complete key catalogue

| Layer | Key Pattern | Type | Owner | Description |
|---|---|---|---|---|
| Stock counter | `stock:{saleId}` | String (int) | InventoryService | Current available stock |
| Sale metadata | `sale:meta:{saleId}` | Hash | SaleService | Active sale fields |
| Sale active flag | `sale:active:{saleId}` | String | SaleService | Hot-path liveness check |
| Rate limit | `rate:{userId}:{saleId}` | Sorted Set | API Gateway | Sliding window per user/sale |
| Session | `session:{userId}` | Hash | OrderService | User session token + state |
| Idempotency | `idem:{userId}:{key}` | String | OrderService | Serialised HTTP response |
| Reservation lock | `resv:lock:{userId}:{saleId}` | String | InventoryService | Duplicate reservation guard |
| Pre-warm flag | `stock:warmed:{saleId}` | String | InventoryService | Pre-warm completion marker |

### Key design principles

**1. Include the owning entity in the key.** `idem:{userId}:{key}` scopes idempotency keys
per user — two users generating the same UUID cannot collide. `rate:{userId}:{saleId}`
scopes rate limiting per user per sale — a buyer hammering Sale A does not affect their
rate limit on Sale B.

**2. Use hash tags for co-location.** Any two keys operated in the same Lua script
or pipeline must land on the same shard. Use `{saleId}` as the hash tag for all
sale-scoped keys:
```
stock:{saleId}         → CRC16("saleId") % 16384
sale:meta:{saleId}     → CRC16("saleId") % 16384   ← same shard
sale:active:{saleId}   → CRC16("saleId") % 16384   ← same shard
```

**3. No key collisions across layers.** Each layer has a distinct prefix.
`stock:` vs `sale:` vs `rate:` vs `session:` vs `idem:` — there is no ambiguity
about which service owns which key.

**4. Keys must be deterministic.** Given the same inputs, the key must always
be the same string. No timestamps, no random suffixes in key names.

---

## 3. TTL Strategy

### TTL catalogue

| Key Pattern | TTL | Reset on Access | Reason |
|---|---|---|---|
| `stock:{saleId}` | `saleEnd + 10 min` | No | Must outlive the sale; 10 min buffer for post-sale ops |
| `sale:meta:{saleId}` | `saleEnd + 10 min` | No | Mirrors stock TTL |
| `sale:active:{saleId}` | `saleEnd + 30 sec` | No | Slightly shorter than meta; cleared quickly post-sale |
| `rate:{userId}:{saleId}` | 60 sec | No | Sliding window; each ZADD entry has its own timestamp |
| `session:{userId}` | 5 min | Yes (rolling) | Active users keep session alive; idle sessions expire |
| `idem:{userId}:{key}` | 24 hours | No | Matches OrderService idempotency contract |
| `resv:lock:{userId}:{saleId}` | 30 sec | No | Short-lived duplicate guard; not a durable lock |
| `stock:warmed:{saleId}` | `saleEnd + 1 min` | No | Pre-warm completion marker |

### TTL violation = correctness bug (for stock keys)

The `stock:{saleId}` key must never expire while a sale is `ACTIVE`. If it expires
mid-sale, the next reservation request triggers a cache miss, falls back to Postgres,
re-warms the key, and continues — but this adds latency and risks a brief window
where concurrent fallbacks compete for the `SELECT FOR UPDATE` lock.

**Prevention:** TTL is set at pre-warm time (`T - 60s` before sale start) as:
```
TTL = saleEnd - NOW() + 600  // seconds; 600 = 10 minute buffer
```
This is calculated server-side in InventoryService at pre-warm time. It is never
hardcoded. If the system clock drifts, the TTL drifts too — which is the correct
behaviour.

### TTL enforcement rule

```
NEVER call EXPIRE on stock:{saleId} after the sale is ACTIVE.

The only writes to stock:{saleId} after activation are:
  1. DECR  (reservation)
  2. INCR  (release/expiry compensation)

Calling EXPIRE again would reset the TTL to a potentially shorter window.
The SET with TTL at pre-warm time is the only TTL-setting operation.
```

---

## 4. Layer 1 — Stock Counter + Lua Scripts

### 4.1 Core reservation script

The atomic check-and-decrement. Executed as a single Redis command — no race
condition possible between the check and the decrement.

```lua
-- Script: STOCK_DECREMENT
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to decrement (usually 1)
-- Returns :
--   -2  cache miss (key does not exist)
--   -1  sold out (stock <= 0)
--   >= 0  remaining stock after decrement

local stock = tonumber(redis.call('GET', KEYS[1]))

if stock == nil then
    return -2   -- key missing: cache miss, fall back to Postgres
end

if stock <= 0 then
    return -1   -- sold out
end

local qty = tonumber(ARGV[1]) or 1

if stock < qty then
    return -1   -- not enough stock for requested quantity
end

redis.call('DECRBY', KEYS[1], qty)
return stock - qty  -- remaining stock after this reservation
```

**Why Lua and not a transaction (`MULTI`/`EXEC`):**
A `WATCH`/`MULTI`/`EXEC` optimistic transaction would require the client to retry
on conflict. Under 50,000 concurrent requests, retry storms become the bottleneck.
The Lua script executes atomically on the Redis thread — no retries, no WATCH
invalidations, no client-side loops. One network round-trip per reservation.

### 4.2 Pre-warm script

Sets the stock counter before a sale goes live. Uses `SET NX` (set if not exists)
to prevent double pre-warm from two racing SaleService pods.

```lua
-- Script: STOCK_PREWARM
-- KEYS[1] : stock:{saleId}
-- KEYS[2] : stock:warmed:{saleId}
-- ARGV[1] : total stock (integer)
-- ARGV[2] : TTL in seconds
-- Returns :
--   1  pre-warm succeeded
--   0  already warmed (another pod beat us)

local already = redis.call('GET', KEYS[2])
if already then
    return 0  -- idempotent: another pod already pre-warmed
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])  -- mark as warmed

return 1
```

**Co-location requirement:** `stock:{saleId}` and `stock:warmed:{saleId}` must share
a shard. Both use the `{saleId}` hash tag — guaranteed same shard by Redis Cluster.

### 4.3 Release script (saga compensation)

Atomically restores stock when a reservation is released by the saga.

```lua
-- Script: STOCK_RELEASE
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to restore
-- ARGV[2] : total_allocated (ceiling — never restore above this)
-- Returns :
--   -2  key missing (sale ended, key expired — no-op)
--   >= 0  new stock level after release

local stock = tonumber(redis.call('GET', KEYS[1]))

if stock == nil then
    return -2  -- sale ended or key evicted — Postgres handles reconciliation
end

local qty   = tonumber(ARGV[1])
local ceil  = tonumber(ARGV[2])
local newStock = math.min(stock + qty, ceil)

redis.call('SET', KEYS[1], newStock)
return newStock
```

**Why `math.min` with ceiling:** Without the ceiling, a compensation replay (the
release event is consumed twice due to Kafka at-least-once delivery) would
increment stock above `total_allocated`. The ceiling enforces the Product aggregate
invariant at the Redis layer.

### 4.4 Reconcile script

Corrects Redis stock when it drifts from Postgres (detected by the reconciliation job).

```lua
-- Script: STOCK_RECONCILE
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : authoritative stock from Postgres
-- ARGV[2] : TTL in seconds (recalculated from saleEnd)
-- Returns :
--   1  reconciled
--   0  key missing — set from Postgres value

local current = redis.call('GET', KEYS[1])

if current == nil then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 0
end

if tonumber(current) ~= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
end

return 1
```

### 4.5 Return code handling (Java)

```java
public ReservationResult reserveStock(SaleId saleId, int quantity) {
    Long result = redisTemplate.execute(
        stockDecrementScript,
        List.of("stock:" + saleId),
        String.valueOf(quantity)
    );

    return switch (result.intValue()) {
        case -2 -> {
            log.warn("Cache miss for saleId={} — falling back to Postgres", saleId);
            yield postgresReserveStock(saleId, quantity);   // fallback path
        }
        case -1 -> ReservationResult.soldOut();
        default -> ReservationResult.success(result.intValue());  // remaining stock
    };
}
```

---

## 5. Layer 2 — Rate Limiter

### 5.1 Algorithm: sliding window with Sorted Set

The sliding window counts requests in a rolling 60-second window. It is more
accurate than a fixed window (which allows 2x the limit at the window boundary)
and simpler than token bucket.

```
Key:   rate:{userId}:{saleId}
Type:  Sorted Set
Score: request timestamp (Unix milliseconds)
Value: unique request ID (UUID)
TTL:   60 seconds
```

### 5.2 Lua script: sliding window check-and-record

```lua
-- Script: RATE_LIMIT_CHECK
-- KEYS[1] : rate:{userId}:{saleId}
-- ARGV[1] : current timestamp in milliseconds
-- ARGV[2] : window size in milliseconds (60000)
-- ARGV[3] : limit (10)
-- ARGV[4] : unique request ID
-- Returns :
--   0  allowed
--   1  rate limited

local key       = KEYS[1]
local now       = tonumber(ARGV[1])
local window    = tonumber(ARGV[2])
local limit     = tonumber(ARGV[3])
local requestId = ARGV[4]
local cutoff    = now - window

-- Remove entries older than the window
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

-- Count current entries in the window
local count = redis.call('ZCARD', key)

if count >= limit then
    return 1  -- rate limited
end

-- Record this request
redis.call('ZADD', key, now, requestId)
redis.call('PEXPIRE', key, window)  -- reset TTL to window size

return 0  -- allowed
```

**Atomicity:** All operations run in a single Lua script — no possibility of a
request being counted twice or the ZCARD check racing with a ZADD from another request.

### 5.3 Fallback: circuit breaker

If Redis is unavailable, the rate limiter circuit breaker opens and fails open
(allows the request). Rate limit failures must not block a flash sale.

```java
public RateLimitResult checkRateLimit(UserId userId, SaleId saleId) {
    try {
        Long result = redisTemplate.execute(
            rateLimitScript,
            List.of("rate:" + userId + ":" + saleId),
            String.valueOf(Instant.now().toEpochMilli()),
            "60000",   // 60 second window
            "10",      // limit
            UUID.randomUUID().toString()
        );
        return result == 0
            ? RateLimitResult.allowed()
            : RateLimitResult.limited(computeRetryAfter());

    } catch (RedisConnectionFailureException e) {
        // Fail open: rate limiter down must not stop sales
        log.error("Rate limiter Redis unavailable — failing open userId={}", userId);
        meterRegistry.counter("rate_limit.redis_failure").increment();
        return RateLimitResult.allowed();  // allow with audit trail
    }
}
```

### 5.4 Rate limit response

```
HTTP 429 Too Many Requests
Retry-After: 47                  ← seconds until oldest entry exits the window
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718448600    ← Unix timestamp when window resets
```

---

## 6. Layer 3 — Session Cache

### 6.1 Key structure

```
Key:   session:{userId}
Type:  Hash
TTL:   300 seconds (5 minutes), rolling — reset on every access
```

### 6.2 Hash fields

| Field | Type | Description |
|---|---|---|
| `token` | String | JWT or opaque session token |
| `userId` | String | Typed UserId (redundant but speeds lookups) |
| `expiresAt` | String | Token expiry as ISO-8601 |
| `lastActive` | String | Last activity timestamp |
| `cartSaleId` | String | Current sale the user is browsing (optional) |

### 6.3 Session operations

```java
// Write session on authentication
public void createSession(UserId userId, String token, Instant expiresAt) {
    String key = "session:" + userId;
    Map<String, String> fields = Map.of(
        "token",      token,
        "userId",     userId.toString(),
        "expiresAt",  expiresAt.toString(),
        "lastActive", Instant.now().toString()
    );
    redisTemplate.opsForHash().putAll(key, fields);
    redisTemplate.expire(key, Duration.ofMinutes(5));
}

// Read session — rolling TTL reset
public Optional<Session> getSession(UserId userId) {
    String key = "session:" + userId;
    Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);

    if (fields.isEmpty()) return Optional.empty();

    // Rolling TTL: reset on every read
    redisTemplate.expire(key, Duration.ofMinutes(5));

    return Optional.of(Session.fromMap(fields));
}

// Invalidate session on logout
public void invalidateSession(UserId userId) {
    redisTemplate.delete("session:" + userId);
}
```

### 6.4 Fallback

On Redis miss or Redis failure: the API gateway re-validates the JWT token directly
(stateless validation). No Postgres lookup needed for session data — the JWT is
self-contained. Redis session cache is an optimisation, not a requirement.

---

## 7. Layer 4 — Idempotency Cache

### 7.1 Key structure

```
Key:   idem:{userId}:{idempotencyKey}
Type:  String (serialised JSON)
TTL:   86400 seconds (24 hours), fixed — never extended
```

The `userId` prefix is non-negotiable. Without it, two users generating the same
UUID v4 (astronomically unlikely but architecturally unsound) would collide.

### 7.2 Two-layer check pattern (Redis + Postgres)

```java
public Optional<CachedResponse> checkIdempotency(UserId userId,
                                                   IdempotencyKey key) {
    String redisKey = "idem:" + userId + ":" + key.value();

    // Layer 1: Redis (fast path — < 1ms)
    String cached = redisTemplate.opsForValue().get(redisKey);
    if (cached != null) {
        return Optional.of(deserialise(cached));
    }

    // Layer 2: Postgres (fallback — key may have been evicted or Redis restarted)
    return idempotencyKeyRepository.findByKey(key)
        .map(record -> {
            // Re-warm Redis cache on Postgres hit
            redisTemplate.opsForValue().set(
                redisKey, record.responsePayload(),
                Duration.ofHours(24)
            );
            return deserialise(record.responsePayload());
        });
}

public void storeIdempotencyResult(UserId userId, IdempotencyKey key,
                                    CachedResponse response) {
    String serialised = serialise(response);
    String redisKey   = "idem:" + userId + ":" + key.value();

    // Write Redis and Postgres in the same operation
    // (Postgres write is in the main order transaction — see OrderService)
    redisTemplate.opsForValue().set(redisKey, serialised, Duration.ofHours(24));
}
```

### 7.3 Why TTL is fixed, not rolling

The 24-hour idempotency window is a business contract, not a caching hint. If a
client retries 23 hours after the original request, it must get the same response.
Rolling the TTL on every read would silently extend the window, potentially beyond
24 hours. The TTL must be set exactly once at write time and never extended.

---

## 8. Layer 5 — Sale Metadata Cache

This layer was implicit in the spec (SaleService Redis cache) but not explicitly
named. It deserves a formal definition because it is the hot path for
`GET /api/v1/sales/{id}/active`.

### 8.1 Key structure

```
Key (metadata) : sale:meta:{saleId}
Type           : Hash
TTL            : saleEnd + 10 minutes (same as stock counter)

Key (active flag) : sale:active:{saleId}
Type              : String ("1" or absent)
TTL               : saleEnd + 30 seconds
```

### 8.2 Hash fields for sale:meta

| Field | Type | Description |
|---|---|---|
| `saleId` | String | UUID |
| `productId` | String | UUID — opaque ref |
| `status` | String | SCHEDULED \| ACTIVE \| ENDED \| ARCHIVED |
| `totalStock` | String | Integer, written at sale creation |
| `saleStart` | String | ISO-8601 |
| `saleEnd` | String | ISO-8601 |
| `version` | String | Optimistic lock version from Postgres |

### 8.3 Hot path: is this sale active?

```java
// GET /api/v1/sales/{id}/active — served from Redis, never Postgres on hot path
public boolean isSaleActive(SaleId saleId) {
    // Single GET on the active flag key — fastest possible check
    String flag = redisTemplate.opsForValue().get("sale:active:" + saleId);

    if (flag != null) {
        return "1".equals(flag);  // present = active
    }

    // Cache miss: load from Postgres, populate both keys
    FlashSale sale = saleRepository.findById(saleId)
        .orElseThrow(() -> new SaleNotFoundException(saleId));

    populateSaleCache(sale);

    return sale.status() == SaleStatus.Active.class;
}

private void populateSaleCache(FlashSale sale) {
    long ttlSeconds = ChronoUnit.SECONDS.between(Instant.now(), sale.saleEnd()) + 600;

    // Write metadata hash
    redisTemplate.opsForHash().putAll(
        "sale:meta:" + sale.id(),
        sale.toCacheMap()
    );
    redisTemplate.expire("sale:meta:" + sale.id(), Duration.ofSeconds(ttlSeconds));

    // Write active flag if ACTIVE
    if (sale.isActive()) {
        redisTemplate.opsForValue().set(
            "sale:active:" + sale.id(), "1",
            Duration.ofSeconds(ttlSeconds - 570)  // 30 sec shorter than meta
        );
    }
}
```

### 8.4 Sale status transition invalidation

When SaleService transitions a sale to `ENDED`, it must immediately delete the active
flag from Redis — it must not wait for TTL expiry.

```java
public void onSaleEnded(SaleId saleId) {
    // Immediate deletion — do not wait for TTL
    redisTemplate.delete("sale:active:" + saleId);

    // Update status in metadata hash
    redisTemplate.opsForHash().put(
        "sale:meta:" + saleId, "status", "ENDED"
    );
}
```

---

## 9. Fallback Strategy

### Decision tree for each layer

```
Layer 1 — Stock Counter

  Redis DECR Lua → returns -2 (miss)
    → Load stock from Postgres stock_levels WHERE product_id=? AND sale_id=?
    → SET stock:{saleId} = current_stock EX {ttl}
    → Re-run Lua script (now hits)
    → If Postgres also fails → return 503 Service Unavailable

  Redis unreachable (connection failure)
    → Circuit breaker opens after 5 consecutive failures
    → SELECT current_stock FROM stock_levels WHERE ... FOR UPDATE
    → UPDATE current_stock = current_stock - qty WHERE version = ?
    → Log: source=POSTGRES_FALLBACK in stock_reservation_log

Layer 2 — Rate Limiter

  Redis unreachable
    → Fail open (allow the request)
    → Increment rate_limit.redis_failure counter
    → Log audit entry with userId, saleId, timestamp
    → Alert if failure rate > 10% over 60 seconds

Layer 3 — Session Cache

  Redis miss or unreachable
    → Validate JWT token directly (stateless)
    → Session data not needed for stateless JWT — no Postgres lookup

Layer 4 — Idempotency Cache

  Redis miss
    → Query idempotency_keys table in Postgres (PK lookup)
    → On hit: re-warm Redis, return stored response
    → On miss: process request normally

  Redis unreachable
    → Go directly to Postgres idempotency_keys
    → Process request if not found
    → Write only to Postgres (Redis re-warmed when it recovers)

Layer 5 — Sale Metadata

  Redis miss
    → Query flash_sales + sale_schedules from sales_db
    → Populate Redis cache
    → Return result

  Redis unreachable
    → All sale queries go to Postgres (latency degrades, correctness preserved)
    → Alert fires: sale_metadata_redis_failure
```

### Circuit breaker configuration

```java
@Bean
public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(50)              // Open after 50% failures
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .slidingWindowType(COUNT_BASED)
        .slidingWindowSize(10)                 // Last 10 calls
        .permittedNumberOfCallsInHalfOpenState(3)
        .build();

    return registry.circuitBreaker("redis-stock", config);
}
```

---

## 10. Stampede Guard

### Problem: thundering herd at sale start

At `T+0` (sale start), all 50,000 users hit `GET /active` simultaneously. If the
Redis key is not yet warmed, all 50,000 requests miss and simultaneously attempt
to load from Postgres — a stampede.

### Solution: probabilistic early refresh

The stock counter is pre-warmed at `T - 60s`. The sale active flag is set at
`T - 60s`. By the time the thundering herd arrives, all keys exist in Redis.

For keys that were not pre-warmed (e.g., cache restarts mid-sale), use probabilistic
early refresh to prevent stampede on TTL expiry:

```java
// Probabilistic early refresh — XFetch algorithm
// Runs before TTL expires, with increasing probability as TTL shrinks
public <T> T getWithStampedeGuard(String key, Duration beta,
                                   Supplier<T> loader, Duration ttl) {
    String raw = redisTemplate.opsForValue().get(key);
    Long remainingTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

    if (raw != null && remainingTtl != null) {
        // Compute recompute probability: higher as TTL approaches 0
        double probability = Math.exp(-remainingTtl / (beta.toSeconds() * Math.log(ttl.toSeconds())));
        double random = ThreadLocalRandom.current().nextDouble();

        if (random > probability) {
            return deserialise(raw);  // Cache hit — serve from Redis
        }
        // Probabilistic early refresh: recompute before TTL expires
    }

    // Cache miss or early refresh: load from source
    T value = loader.get();
    redisTemplate.opsForValue().set(key, serialise(value), ttl);
    return value;
}
```

**For stock counters specifically:** The pre-warm at T-60s is the primary stampede
protection. The probabilistic refresh is a backstop for unexpected evictions.

---

## 11. Eviction Policy

### Policy: `allkeys-lru`

When Redis reaches the 4 GB memory cap, it evicts the least-recently-used key
across all keyspaces. This is deliberately chosen over `volatile-lru` (evicts only
keys with TTL set) because:

- Every key in this system has a TTL
- `allkeys-lru` and `volatile-lru` behave identically when all keys have TTLs
- `allkeys-lru` is more predictable — no risk of failing to evict if TTL-less keys accumulate

### Eviction priority

Under memory pressure, Redis evicts in LRU order. The keys evicted first are those
least recently accessed. In practice under a flash sale:

```
Rarely evicted:   stock:{activeSaleId}         ← accessed on every reservation
                  sale:active:{activeSaleId}    ← accessed on every request
                  session:{activeUserId}         ← accessed on every request

Likely evicted:   sale:meta:{endedSaleId}       ← TTL usually expires first
                  idem:{userId}:{oldKey}         ← old idempotency keys
                  session:{inactiveUserId}       ← sessions that have gone cold
```

### Memory sizing

```
Estimate for 1 active sale with 10,000 concurrent users:

stock counters:         10 sales × 8 bytes     =      80 bytes
sale metadata:          10 sales × 500 bytes   =   5,000 bytes
session cache:      10,000 users × 200 bytes   =   2 MB
idempotency cache:  10,000 keys × 1,000 bytes  =  10 MB
rate limit sets:    10,000 users × 500 bytes   =   5 MB

Total: ~17 MB for a 10,000-user sale
4 GB cap supports ~235 simultaneous active sales at this user load
```

---

## 12. Spring Boot Implementation

### RedisConfig

```java
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${redis.nodes}") List<String> nodes) {

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(nodes);
        clusterConfig.setMaxRedirects(3);

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(500))   // Fast timeout — fail fast
            .readFrom(ReadFrom.REPLICA_PREFERRED)      // Read from replicas when possible
            .build();

        return new LettuceConnectionFactory(clusterConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(
            RedisConnectionFactory factory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    // Lua script beans — loaded once at startup, reused by SHA
    @Bean
    public RedisScript<Long> stockDecrementScript() {
        return RedisScript.of(loadScript("lua/stock_decrement.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> stockPrewarmScript() {
        return RedisScript.of(loadScript("lua/stock_prewarm.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> stockReleaseScript() {
        return RedisScript.of(loadScript("lua/stock_release.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> rateLimitScript() {
        return RedisScript.of(loadScript("lua/rate_limit.lua"), Long.class);
    }

    private String loadScript(String path) {
        try {
            return new ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Lua script: " + path, e);
        }
    }
}
```

### StockCounterService (InventoryService)

```java
@Service
@RequiredArgsConstructor
public class StockCounterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> stockDecrementScript;
    private final RedisScript<Long> stockPrewarmScript;
    private final RedisScript<Long> stockReleaseScript;
    private final StockLevelRepository stockLevelRepository;
    private final CircuitBreaker redisCircuitBreaker;

    public ReservationResult reserve(SaleId saleId, int quantity) {
        return redisCircuitBreaker.executeSupplier(
            () -> reserveViaRedis(saleId, quantity),
            e  -> reserveViaPostgres(saleId, quantity)   // fallback
        );
    }

    private ReservationResult reserveViaRedis(SaleId saleId, int quantity) {
        Long result = redisTemplate.execute(
            stockDecrementScript,
            List.of(stockKey(saleId)),
            String.valueOf(quantity)
        );

        return switch (result.intValue()) {
            case -2 -> {
                // Cache miss — load from Postgres, re-warm, retry once
                int stock = loadAndWarmFromPostgres(saleId);
                if (stock < quantity) yield ReservationResult.soldOut();
                yield reserveViaRedis(saleId, quantity);   // exactly one retry
            }
            case -1 -> ReservationResult.soldOut();
            default -> ReservationResult.success(result.intValue());
        };
    }

    private ReservationResult reserveViaPostgres(SaleId saleId, int quantity) {
        // SELECT FOR UPDATE fallback
        return stockLevelRepository.decrementWithLock(saleId, quantity);
    }

    public void prewarm(SaleId saleId, int totalStock, Instant saleEnd) {
        long ttlSeconds = ChronoUnit.SECONDS.between(Instant.now(), saleEnd) + 600;

        Long result = redisTemplate.execute(
            stockPrewarmScript,
            List.of(stockKey(saleId), prewarmFlag(saleId)),
            String.valueOf(totalStock),
            String.valueOf(ttlSeconds)
        );

        if (result == 1L) {
            log.info("Stock pre-warmed saleId={} stock={} ttl={}s",
                     saleId, totalStock, ttlSeconds);
        } else {
            log.info("Stock already pre-warmed by another pod saleId={}", saleId);
        }
    }

    public void release(SaleId saleId, int quantity, int totalAllocated) {
        redisTemplate.execute(
            stockReleaseScript,
            List.of(stockKey(saleId)),
            String.valueOf(quantity),
            String.valueOf(totalAllocated)
        );
    }

    private String stockKey(SaleId saleId)    { return "stock:" + saleId; }
    private String prewarmFlag(SaleId saleId) { return "stock:warmed:" + saleId; }

    private int loadAndWarmFromPostgres(SaleId saleId) {
        StockLevel level = stockLevelRepository.findBySaleId(saleId)
            .orElseThrow(() -> new SaleNotFoundException(saleId));
        long ttl = ChronoUnit.SECONDS.between(Instant.now(),
                       level.saleEnd().plusSeconds(600));
        redisTemplate.opsForValue().set(
            stockKey(saleId),
            String.valueOf(level.currentStock()),
            Duration.ofSeconds(ttl)
        );
        return level.currentStock();
    }
}
```

### RateLimiterService (API Gateway / SaleService)

```java
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public RateLimitResult check(UserId userId, SaleId saleId) {
        try {
            Long result = redisTemplate.execute(
                rateLimitScript,
                List.of("rate:" + userId + ":" + saleId),
                String.valueOf(System.currentTimeMillis()),
                "60000",
                "10",
                UUID.randomUUID().toString()
            );

            if (result == 0L) return RateLimitResult.allowed();

            long retryAfterSeconds = computeRetryAfter(userId, saleId);
            return RateLimitResult.limited(retryAfterSeconds);

        } catch (Exception e) {
            log.error("Rate limiter Redis error — failing open userId={}", userId, e);
            return RateLimitResult.allowed();
        }
    }

    private long computeRetryAfter(UserId userId, SaleId saleId) {
        // Find the oldest entry in the sorted set — that's when the window opens
        Set<ZSetOperations.TypedTuple<String>> oldest =
            redisTemplate.opsForZSet()
                .rangeWithScores("rate:" + userId + ":" + saleId, 0, 0);

        if (oldest == null || oldest.isEmpty()) return 60L;

        double oldestScore = oldest.iterator().next().getScore();
        long windowExpiryMs = (long) oldestScore + 60_000L;
        return Math.max(1L, (windowExpiryMs - System.currentTimeMillis()) / 1000L);
    }
}
```

---

## 13. Operational Runbook

### Memory monitoring

```bash
# Check memory usage per shard
redis-cli -c -h shard0 INFO memory | grep used_memory_human

# Check eviction statistics
redis-cli -c INFO stats | grep evicted_keys

# Expected: evicted_keys should be near 0 during a sale
# Alert: evicted_keys increasing rapidly = memory cap too low
```

### Key inspection during a live sale

```bash
# Check stock level for a specific sale
redis-cli -c GET "stock:{sale-uuid-here}"

# Check if sale is active
redis-cli -c GET "sale:active:{sale-uuid-here}"

# Check rate limit set size for a user
redis-cli -c ZCARD "rate:{user-uuid}:{sale-uuid}"

# Check TTL remaining on stock counter
redis-cli -c TTL "stock:{sale-uuid-here}"
# Expected: positive integer (seconds until expiry)
# Alarm: 0 or -1 during an ACTIVE sale = TTL bug
```

### Pre-sale checklist

```bash
# T-5 minutes: verify Redis cluster health
redis-cli -c CLUSTER INFO | grep cluster_state
# Expected: cluster_state:ok

# T-5 minutes: pre-scale HPA for InventoryService + OrderService
kubectl scale deployment inventory-service --replicas=10

# T-1 minute: verify stock key is pre-warmed
redis-cli -c GET "stock:{saleId}"
# Expected: integer equal to totalStock

# T-0: watch stock drain in real time
watch -n 1 "redis-cli -c GET 'stock:{saleId}'"
```

### Post-sale reconciliation

```bash
# Compare Redis stock to Postgres stock after sale ends
# (Run reconciliation job — triggers STOCK_RECONCILE Lua script)
kubectl exec -it inventory-service-pod -- \
  java -cp app.jar com.flashsale.inventory.ReconciliationJob --saleId={saleId}
```

### DLQ / alert: Redis memory > 80%

```bash
# Find largest key patterns consuming memory
redis-cli -c --bigkeys

# Manually expire stale idempotency keys if needed
redis-cli -c SCAN 0 MATCH "idem:*" COUNT 1000
# Then evaluate age and delete if safe
```

---

*Redis design derived from Final-Spec-Council.md v2.0.*
*Redis is never the source of truth. Postgres owns durable state.*
*All Lua scripts are idempotent. All fallbacks degrade gracefully.*
*Next: Spring Boot service skeletons wired to Redis and Kafka.*