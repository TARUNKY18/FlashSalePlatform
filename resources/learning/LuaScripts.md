# Lua Scripts — Interview Notes
## Flash Sale Platform
**Scripts:** `stock_decrement.lua` · `stock_prewarm.lua` · `stock_release.lua` · `stock_reconcile.lua`
**Location:** `services/inventory-service/src/main/resources/lua/`
**Source of truth:** PostgreSQL `inventory_db` · Redis is the fast gate, never the record

---

## Why Redis Lua is atomic

Redis executes all commands on **one thread**. When a Lua script arrives via `EVALSHA`, that thread runs the entire script to completion before touching the next command in the queue. No other client command can interleave.

This is not locking. Redis has no locks. It is serialisation by design.

### The gap problem — why two separate commands are not enough

```
Thread A:  GET stock:{saleId}   → reads 1
Thread B:  GET stock:{saleId}   → reads 1   ← gap: another command ran here
Thread A:  DECRBY stock 1       → stock = 0  (sold to A)
Thread B:  DECRBY stock 1       → stock = -1 (OVERSOLD)
```

Between `GET` and `DECRBY` — two separate commands — another client can execute. Both threads see `stock = 1`. Both decrement. One unit sold twice.

### Why the Lua script closes the gap

```
Thread A:  EVALSHA (GET → check → DECRBY all in one block)
Thread B:  ← blocked, cannot run until A's script finishes
```

The `GET` and `DECRBY` are inside one script. One thread. No window. Impossible to race.

### Why `WATCH`/`MULTI`/`EXEC` is the wrong answer

The obvious Redis alternative is optimistic locking: `WATCH` the key, `MULTI`/`EXEC` the transaction, retry if the key changed. Under 50,000 concurrent requests against one stock counter, almost every transaction fails and retries. The retry storm makes contention worse. Lua does not retry — it executes once, atomically.

---

## Script 1 — `stock_decrement.lua`

**Problem it solves:** Prevent overselling. Check stock and decrement in one indivisible operation.

**Called by:** InventoryService on every reservation request.

```lua
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to reserve (integer >= 1)
-- Returns : -2 = cache miss | -1 = sold out | >= 0 = remaining stock

local stock = tonumber(redis.call('GET', KEYS[1]))
```
`redis.call()` runs a Redis command from Lua. Redis stores everything as strings — `tonumber()` converts `"1000"` to `1000`. If the key does not exist, `GET` returns Redis nil → Lua `false` → `tonumber(false)` = `nil`.

```lua
if stock == nil then
    return -2
end
```
Key missing. Three causes: never pre-warmed, TTL expired (sale ended), Redis restarted. Return `-2` = cache miss. InventoryService falls back to `SELECT FOR UPDATE` on Postgres and re-warms Redis.

```lua
if stock <= 0 then
    return -1
end
```
Floor check. Zero or negative = sold out. Return `-1`. `<= 0` not `== 0` — defensive against any bug that puts stock below zero.

```lua
local qty = tonumber(ARGV[1]) or 1
```
Requested quantity. `or 1` is a Lua default — if caller passes no quantity, reserve 1 unit.

```lua
if stock < qty then
    return -1
end
```
Quantity check. 2 units left, buyer wants 3 → sold out. Without this, `DECRBY` would produce `-1`.

```lua
redis.call('DECRBY', KEYS[1], qty)
return stock - qty
```
All checks passed. Decrement. Return new stock level computed in Lua — no second `GET` needed.

### Return code handling

| Code | Meaning | InventoryService action |
|---|---|---|
| `-2` | Cache miss — key not in Redis | `SELECT FOR UPDATE` on Postgres + re-warm Redis |
| `-1` | Sold out | Return HTTP `409 SOLD_OUT` |
| `≥ 0` | Success — remaining stock | Write reservation row + publish `StockReserved` to Kafka |

### The floor guarantee

`stock <= 0` is checked before `DECRBY`. The check and decrement are one atomic operation. Stock can never go below zero via this script. This is the oversell prevention mechanism — not a lock, not a transaction, not retry logic.

---

## Script 2 — `stock_prewarm.lua`

**Problem it solves:** Idempotent pre-warm. Multiple service pods all receive the "sale starting" event simultaneously and try to set the stock counter. Only the first one should write.

**Called by:** SaleService, 60 seconds before sale start.

```lua
-- KEYS[1] : stock:{saleId}        — the counter
-- KEYS[2] : stock:warmed:{saleId} — the completion marker (same shard via hash tag)
-- ARGV[1] : totalStock
-- ARGV[2] : TTL seconds
-- Returns : 1 = warmed | 0 = already warmed by another pod

local already = redis.call('GET', KEYS[2])
if already then
    return 0
end
```
Check the completion marker. If it exists, another pod already ran this script. Return `0` = skip. This check and the writes below run atomically — two pods cannot both see `nil` simultaneously and both proceed.

```lua
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('SET', KEYS[2], '1',     'EX', ARGV[2])
return 1
```
Set stock counter and completion marker with the same TTL (calculated from sale end time). Both expire together when the sale ends. The slot is clean.

### Why a separate marker key and not just check `KEYS[1]`?

`stock:{saleId}` might already exist if the sale is running or stock was adjusted. The marker is an unambiguous signal that pre-warm completed — completely separate from the actual stock value.

### Hash tags — why `{saleId}` is in the key names

Redis Cluster distributes keys across shards. A Lua script that touches keys on different shards produces a `CROSSSLOT` error. Redis only hashes the content between `{` and `}`. Both `stock:{saleId}` and `stock:warmed:{saleId}` hash on `saleId` → same shard → multi-key script works.

---

## Script 3 — `stock_release.lua`

**Problem it solves:** Saga compensation and reservation expiry. When an order fails or a reservation expires, stock must be returned — correctly, even if the event is delivered twice.

**Called by:** InventoryService on `PaymentFailed` event or reservation TTL expiry.

```lua
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to restore
-- ARGV[2] : total_allocated ceiling (prevents over-restore on replay)
-- Returns : -2 = key missing (sale ended) | >= 0 = new stock level

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -2
end
```
Key gone = sale ended. No point restoring stock to a finished sale. Log and return `-2` — not an error.

```lua
local qty      = tonumber(ARGV[1])
local ceiling  = tonumber(ARGV[2])
local newStock = math.min(stock + qty, ceiling)
redis.call('SET', KEYS[1], newStock)
return newStock
```

`math.min(stock + qty, ceiling)` is the at-least-once idempotency guard.

**Why this matters:** Kafka delivers at-least-once. A `StockRelease` event can arrive twice. Without the ceiling, two deliveries of "restore 5 units" add 10 units instead of 5.

With the ceiling (= original total stock): stock can never be restored above what was ever available. The ceiling does not make individual event replays perfectly idempotent, but it prevents catastrophic accumulation — stock cannot climb above the total that was allocated for the sale.

### Why no TTL is set on the `SET`?

The key received its TTL during `stock_prewarm.lua`. That TTL is still running toward the sale end time. Resetting it here would extend the key's lifetime unintentionally.

---

## Script 4 — `stock_reconcile.lua`

**Problem it solves:** Redis and Postgres can drift. AOF replay may miss writes after a restart. A bug may cause a Redis write to fail while Postgres succeeded. This script corrects Redis when it disagrees with the authoritative value.

**Called by:** InventoryService background job, periodically.

```lua
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : authoritative stock value from Postgres
-- ARGV[2] : TTL seconds (recalculated from saleEnd)
-- Returns : 1 = key existed | 0 = key was missing, set fresh

local current = redis.call('GET', KEYS[1])
if current == nil then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 0
end
```
Key missing — Redis may have restarted. Set it fresh from Postgres with a new TTL. Return `0`.

```lua
if tonumber(current) ~= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
end
return 1
```
Key exists but values differ — drift detected. Overwrite Redis with Postgres value.

`tonumber()` on both sides is deliberate. String comparison would falsely flag `"10"` vs `"010"` as different. Number comparison handles both correctly.

Return `1` whether or not an update was made — "key existed."

---

## The four-script lifecycle

```
60s before sale start
  └─ stock_prewarm.lua    Set stock:{saleId} = totalStock with TTL
                          Idempotent — only first pod writes

During live sale (thousands of times per second)
  └─ stock_decrement.lua  Atomic check-and-decrement
                          Returns -2 / -1 / ≥0
                          -2 path: Postgres fallback + re-warm

On cancellation or reservation expiry
  └─ stock_release.lua    Restore stock with ceiling guard
                          Handles at-least-once Kafka delivery

Background (periodic reconciliation job)
  └─ stock_reconcile.lua  Correct Redis drift against Postgres
                          Handles Redis restart / AOF gap
```

---

## KEYS and ARGV — why the separation matters

Every Redis Lua script receives two arrays: `KEYS` and `ARGV`. This is not a convention — it is enforced by Redis Cluster. Before routing a script to a shard, Redis must know which keys it touches. Keys listed in `KEYS[]` are used for routing. Arguments in `ARGV[]` are additional data that does not affect routing.

Passing a key in `ARGV` instead of `KEYS` bypasses the routing check and causes `CROSSSLOT` errors in cluster mode. All four scripts list their key names in `KEYS` correctly.

---

## Interview questions

**"Walk me through stock_decrement.lua line by line."**
Start with the contract (`KEYS[1]`, `ARGV[1]`, three return codes), explain the nil check as cache-miss detection, explain the floor check as the actual oversell prevention, explain the quantity check for multi-unit reservations, and end with the DECRBY + computed return. Emphasise that the GET and DECRBY are one atomic block — no window, no race.

**"Why Lua and not WATCH/MULTI/EXEC?"**
Optimistic locking with `WATCH` requires retry on transaction failure. At 50,000 concurrent requests against one key, almost every `EXEC` fails. Retry storms compound the contention. Lua does not retry — it serialises execution. One script, one result, no failure modes from contention.

**"What does -2 mean and why does it exist?"**
Cache miss — the key does not exist in Redis. Three causes: never pre-warmed, TTL expired, Redis restarted. The script cannot distinguish them; it signals the caller. InventoryService falls back to `SELECT FOR UPDATE` on Postgres, gets the authoritative stock, and re-warms Redis. `-2` is the bridge between the fast Redis path and the correct Postgres path.

**"How does stock_prewarm.lua prevent multiple pods from writing the same key?"**
A completion marker key (`stock:warmed:{saleId}`) acts as a distributed write-once flag. The script checks for the marker atomically with the write — inside one Lua execution. Two pods cannot simultaneously see the marker absent and both proceed; one will find the marker set by the time it runs.

**"What is the ceiling in stock_release.lua for?"**
Idempotency against at-least-once Kafka delivery. A `StockRelease` event can arrive twice. Without the ceiling, duplicate events add too much stock. The ceiling (original total stock) caps the restoration — stock can never be restored above what was ever available, regardless of how many duplicate events arrive.

**"What is stock_reconcile.lua compensating for?"**
Redis and Postgres can drift. AOF persistence (`appendfsync everysec`) means up to 1 second of writes can be lost on a crash. A crash that loses 50 reservation decrements leaves Redis showing 50 more units available than Postgres knows were sold. The reconciliation job detects this and corrects Redis. Redis is never the source of truth — Postgres always wins on disagreement.

**"Why do all your key names use {saleId} in braces?"**
Hash tags for Redis Cluster routing. Cluster distributes keys across shards using `CRC16(key) % 16384`. By default the entire key name is hashed. For a multi-key Lua script, all keys must land on the same shard. Redis only hashes the substring between `{` and `}` when braces are present. `stock:{saleId}` and `stock:warmed:{saleId}` both hash on `saleId` — same shard, guaranteed. Without hash tags, a multi-key script produces a `CROSSSLOT` error.

---

*Source: `services/inventory-service/src/main/resources/lua/` (four files)*
*ADR-001 (Lua atomic decrement), ADR-003 (SELECT FOR UPDATE fallback)*