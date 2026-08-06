# ADR-020: Pre-Warm Architecture

**Status:** Proposed — Ready for Review

**Date:** 2026-08-05

**Owner:** Lead Architect

**Scope:** Inventory stock-counter pre-warm only

**Implementation authorization:** None. This ADR contains no implementation and
does not authorize implementation until it is approved.

---

## Decision context

This decision is based on the current repository architecture and verified
InventoryService implementation:

- PostgreSQL `inventory_db.stock_levels.current_stock` and the persisted
  StockLevel `version` are authoritative.
- Redis is a disposable projection, not a stock authority.
- The active projection pair is `stock:{saleId}` and
  `stock:version:{saleId}`; both keys use the same Redis Cluster hash tag.
- Durable decrements are serialized under the Product aggregate lock, commit in
  PostgreSQL, and then synchronize Redis using the committed StockLevel
  revision.
- Projection synchronization applies equal or newer durable revisions, ignores
  older revisions, preserves the stock-key TTL, invalidates revisionless stock,
  and does not recreate a missing projection.
- SaleService owns the persisted sale schedule and the sale lifecycle.
- The platform architecture already uses `sale-events` for asynchronous
  SaleService state notifications. No new broker, topic, database, endpoint, or
  service is introduced by this decision.
- The required target remains that the stock projection is live 60 seconds
  before `saleStart`, with expiration at `saleEnd + 10 minutes`.

The existing unintegrated `stock-prewarm.lua` is evidence of the original
intent, not an approved current contract. It writes `stock:{saleId}` plus
`stock:warmed:{saleId}`, accepts an externally supplied stock count, and creates
no revision key. Used unchanged, the first revision-fenced synchronization can
invalidate its revisionless stock. Its marker-only idempotency also cannot
prevent stale initialization from conflicting with the current durable
revision model.

This ADR supersedes only the pre-warm-specific marker, ownership, source, and
trigger statements in older planning/design documents. It amends ADR-017 to
register InventoryService as a consumer of pre-warm-due integration events on
the `sale-events` topic. It does not change the approved decrement,
durable-authority, projection-synchronization, release, reconciliation,
reservation, or sale-lifecycle contracts.

---

## 1. Problem statement

InventoryService needs a Redis stock projection before a sale becomes active so
sale-start traffic does not begin on a cold key. The repository does not yet
define one compatible contract for who triggers pre-warm, when it runs, where
stock and revision come from, who owns TTL policy, how concurrent triggers are
made safe, or how initialization interacts with the active revision fence.

The decision must prevent three unsafe outcomes:

1. SaleService or a trigger payload becoming an alternate stock authority.
2. A revisionless or stale pre-warm overwriting a newer Redis projection.
3. Competing pods extending TTL or resetting stock through repeated pre-warm
   calls.

## 2. Goals

- Have a valid stock-and-revision projection available by `preWarmAt`, the
  T-60-second readiness deadline, under normal operation.
- Initialize Redis only from one PostgreSQL snapshot containing current stock
  and its matching persisted revision.
- Keep SaleService as schedule authority and InventoryService as stock and Redis
  key owner.
- Make duplicate, concurrent, delayed, and indeterminate trigger delivery safe.
- Preserve the current Redis Cluster key format, durable-authority flow, and
  revision-fencing rules.
- Give both projection keys the same fixed absolute expiration derived from the
  sale end.
- Fail without corrupting PostgreSQL or fabricating a successful pre-warm.

## 3. Non-goals

- Implementing this ADR.
- Changing the request-time decrement or durable PostgreSQL decrement flow.
- Restoring legacy request-time fallback/re-warming contracts.
- Implementing release, reconciliation, Reservation/Week 4, an Inventory REST
  API, or a synchronous internal endpoint.
- Changing SaleService activation, cancellation, force-end, or rescheduling
  behavior.
- Adding a scheduler database, cross-service database read, Redis lock, leader
  election, new Kafka topic, new service, or new Redis deployment.
- Defining retries, DLQ policy, alert transport, or operational reconciliation
  beyond the pre-warm result and failure boundary.
- Using pre-warm to repair a missing projection after the sale is active.

## 4. Trigger source

The authoritative trigger source is the SaleService scheduler operating from
SaleService's persisted sale schedule. SaleService, not InventoryService, owns
the meaning of `saleStart`, `saleEnd`, and `SCHEDULED`.

When a scheduled sale becomes due for pre-warm, the scheduler publishes a
pre-warm-due integration event on the existing `sale-events` channel, keyed by
`saleId`. InventoryService consumes only the facts needed to invoke its inbound
pre-warm use case:

- event identifier and occurrence time;
- `saleId`;
- `productId`;
- `saleStart`;
- `saleEnd`.

The event must not carry authoritative stock, revision, or a caller-computed
TTL. If a legacy or broader event contains `totalStock`, InventoryService must
not use that value to initialize Redis.

This event is distinct from `SaleStarted`, which occurs too late, and from the
creation-time `SaleScheduled` domain event, which records that a schedule was
created rather than that the T-60 execution boundary has been reached.

## 5. Trigger timing

The readiness deadline is:

```text
preWarmAt = saleStart - 60 seconds
```

The projection must be live by `preWarmAt`. The SaleService scheduler must emit
the pre-warm-due event before `preWarmAt`, with sufficient lead time for
InventoryService to complete execution and establish the projection before the
deadline. The scheduler evaluates durable `SCHEDULED` sales and emits at least
one pre-warm-due event in the execution window before `saleStart`. Multiple
scheduler pods and repeated scans may emit duplicates; duplicates are expected,
not exceptional.

InventoryService validates timing with its injected clock:

- before the pre-warm execution window: do not mutate Redis; report `NOT_DUE`;
- within the pre-warm execution window and before `saleStart`: execute pre-warm,
  targeting completion by `preWarmAt`;
- at or after `saleStart`: do not create or reset the projection; report
  `MISSED_WINDOW`.

A delayed event that still arrives before `saleStart` is executed and reported
as late. Pre-warm never changes sale status and sale activation does not wait
synchronously for InventoryService.

## 6. Authoritative stock source

The only stock value permitted for pre-warm is
`inventory_db.stock_levels.current_stock`, reached through the Product aggregate
and the existing application-owned `ProductRepository` port.

The source is explicitly not:

- SaleService `totalStock`;
- the trigger payload;
- `StockLevel.totalAllocated`;
- an existing Redis value;
- a hard-coded initial allocation.

Product and StockLevel existence and ownership must be validated before any
Redis mutation. No independent StockLevel repository or cross-service database
query is permitted.

## 7. Initial revision source

The initial Redis revision is the persisted `stock_levels.version` read from the
same Product/StockLevel snapshot as `current_stock`. It is never assumed to be
zero and is never derived from a SaleService version, event version, Product
version, clock, or Redis state.

The stock value and revision form one indivisible authoritative snapshot for
the pre-warm attempt.

## 8. TTL ownership

SaleService owns the `saleEnd` fact. InventoryService owns the stock-projection
TTL policy and is the only service allowed to apply TTL to Inventory-owned
stock keys.

At execution, InventoryService derives one fixed absolute expiration:

```text
expiresAt = saleEnd + 10 minutes
ttl       = expiresAt - InventoryService clock now
```

The TTL must be positive. `stock:{saleId}` and
`stock:version:{saleId}` receive the same absolute expiration in the same atomic
Redis operation.

Pre-warm is the only operation that establishes this expiration. Duplicate
pre-warm calls do not extend it. Decrement does not touch it. Projection
synchronization preserves the stock expiration and mirrors it to the revision
key. No persistent stock key is valid for a pre-warmed sale. A key present without a
TTL or with a TTL inconsistent with the expected `expiresAt` is an integrity
violation; the pre-warm atomic operation treats such a key as invalid and fails
closed.

## 9. Concurrency model

Pre-warm uses no JVM mutex, distributed lock, database lock spanning Redis I/O,
or leader election.

Concurrency is handled as follows:

1. Multiple SaleService pods may emit the same logical trigger.
2. Multiple InventoryService pods may read authoritative snapshots and invoke
   Redis concurrently.
3. Each Redis attempt is one atomic Lua execution over the same-slot stock and
   revision keys.
4. A strictly older incoming revision cannot overwrite a newer stored
   revision.
5. An equal-revision pre-warm is a no-op; it must not reset stock or TTL.
6. A newer incoming revision may update the pair but must preserve the existing
   absolute expiration.
7. If a durable decrement races abnormally with pre-warm, PostgreSQL still
   determines the client-visible result and its post-commit synchronization
   remains the convergence mechanism.

The normal lifecycle prevents buyer decrements before `saleStart`; the fence is
still required so correctness does not depend only on that timing assumption.

## 10. Failure semantics

- Invalid identifiers, invalid sale windows, non-positive derived TTL, missing
  Product, or missing Product-owned StockLevel are deterministic failures. No
  Redis mutation occurs.
- PostgreSQL read failure is reported as durable-stock unavailability. Redis is
  not consulted and no alternate stock value is used.
- Invalid stored numeric stock or revision is a deterministic Redis projection
  failure and fails closed; it is not silently overwritten.
- A partial pair caused by independent key loss is untrusted. While still in
  the pre-warm window, the atomic pre-warm operation may remove the partial
  state and recreate the pair from the authoritative snapshot with the fixed
  expiration, provided the incoming revision is not older than any revision
  stored in the surviving key. If the incoming revision is older, the partial
  state is left in place and the attempt fails closed.
- Redis connection loss, timeout, serialization failure, null result, or
  unknown result is reported as pre-warm unavailability. Because execution may
  have completed before a transport failure was observed, no compensating
  delete or blind in-call retry is permitted.
- A later duplicate trigger may safely retry while the pre-warm window remains
  open.
- Pre-warm failure never mutates PostgreSQL and never converts the Redis
  projection into an authority. Sale activation remains independent. If the
  projection is absent, the existing request path continues to use PostgreSQL
  for correctness, with degraded latency and throughput.
- Every failed or late outcome must be observable; this ADR does not select the
  alerting transport.

Outcome classification for transport acknowledgement:

- Terminal (acknowledge; do not redeliver): all deterministic failures (invalid
  identifiers, invalid sale window, non-positive TTL, missing Product, missing
  Product-owned StockLevel, invalid stored state); `MISSED_WINDOW`; and all
  successful outcomes (`WARMED`, `UPDATED`, `ALREADY_CURRENT`, `STALE_IGNORED`).
- Retryable (withhold acknowledgement; redeliver is safe): PostgreSQL read
  failure; Redis transport failure (connection loss, timeout, null or unknown
  result).
- `NOT_DUE` is terminal for the current delivery; whether the scheduler
  redelivers is outside InventoryService responsibility.

This classification governs acknowledgement only; it does not define retry
count, backoff, or DLQ policy.

## 11. Idempotency

Idempotency is state-based and revision-fenced. No idempotency table and no
`stock:warmed:{saleId}` completion marker are required.

For one authoritative `(saleId, currentStock, revision, expiresAt)` snapshot:

- first call against two missing keys initializes both keys atomically;
- concurrent or repeated equal-revision calls return `ALREADY_CURRENT` without
  writing either key or extending TTL;
- calls carrying an older revision return `STALE_IGNORED`;
- a call carrying a newer revision may return `UPDATED` while retaining the
  established absolute expiration;
- an indeterminate first result can be followed by the same call safely.

The event identifier is retained for tracing and message-level observability;
it is not a second stock authority and does not require a new durable
deduplication store.

## 12. Redis key ownership

InventoryService exclusively owns:

- `stock:{saleId}` — projected current stock;
- `stock:version:{saleId}` — persisted StockLevel revision associated with the
  projection.

Both keys must use exactly the same `{saleId}` Redis Cluster hash tag and must be
read or mutated together by the pre-warm Lua operation.

`stock:warmed:{saleId}` is not part of the approved pre-warm architecture.
SaleService must not read, create, update, expire, or delete Inventory-owned
stock keys. InventoryService must not mutate SaleService-owned active-sale or
metadata keys.

## 13. Interaction with revision fencing

Pre-warm is the sole authorized creator of a missing stock-and-revision pair.
It does not weaken or bypass the existing projection fence.

The atomic pre-warm operation follows these rules:

| Redis state | Incoming durable revision | Required outcome |
|---|---:|---|
| Both keys missing | Any valid revision | Initialize stock and revision with one expiration |
| Both keys valid | Lower than stored | `STALE_IGNORED`; no mutation |
| Both keys valid | Equal to stored | `ALREADY_CURRENT`; no mutation and no TTL extension |
| Both keys valid | Higher than stored | Update stock and revision; preserve existing expiration |
| Exactly one key present | Not older than surviving key's revision | Remove partial state and initialize from the authoritative snapshot while still pre-start |
| Exactly one key present | Older than surviving key's revision | Fail closed; do not remove or overwrite surviving state |
| Either value invalid, or either key missing TTL, or TTLs inconsistent | Any revision | Fail closed; no silent overwrite |

The existing post-commit projection synchronization retains its current,
different equal-revision rule: equal revisions may apply there to support
idempotent repair. Pre-warm uses equal-revision no-op semantics because its job
is initialization, and resetting an already-live counter would be unsafe.

After `saleStart`, projection creation is forbidden to this use case. Missing
or revisionless state continues to follow the active synchronization contract;
this ADR does not turn pre-warm into reconciliation.

When both keys are missing and pre-warm initializes from a snapshot that
predates a concurrently committed durable revision, the post-commit projection
synchronization is the convergence mechanism: the next successful durable
decrement's sync applies the newer revision, correcting the stale
initialization. The `MISSED_WINDOW` fence bounds any stale pre-warm window to
the pre-`saleStart` period.

## 14. Required application contracts

The pre-warm implementation requires only these application-layer contracts:

1. An inbound `PreWarmStockUseCase` accepting a command containing
   `ProductId`, `SaleId`, `saleStart`, and `saleEnd`. Stock, revision, and TTL are
   deliberately absent from caller authority.
2. An application result that distinguishes at least `WARMED`, `UPDATED`,
   `ALREADY_CURRENT`, `STALE_IGNORED`, `NOT_DUE`, and `MISSED_WINDOW`.
3. Reuse of the existing application-owned `ProductRepository` to load the
   Product aggregate and resolve its owned StockLevel. No child repository is
   added.
4. A new outbound `StockPreWarmPort` accepting `SaleId`, authoritative
   `StockCount`, authoritative StockLevel revision, and the application-derived
   expiration/TTL.
5. An infrastructure-neutral pre-warm result and unavailable exception. No
   Spring Data, Redis, Kafka, JPA, or transport type may cross these contracts.
6. An injected clock for deterministic timing and TTL calculation.

The pre-warm use case is separate from `StockCounterService` and
`StockProjectionSyncPort`; neither existing contract changes responsibility.

## 15. Required infrastructure contracts

The complete production path requires the following adapters within already
approved infrastructure:

1. SaleService scheduler/publisher: finds durable `SCHEDULED` sales entering the
   T-60 window and publishes the pre-warm-due event to existing `sale-events`,
   keyed by `saleId`.
2. InventoryService `sale-events` consumer: maps the integration event into the
   inbound pre-warm command. It acknowledges only a recognized terminal result;
   transport redelivery remains safe because the use case is idempotent.
3. Existing Product persistence adapter: supplies current stock and StockLevel
   revision from one aggregate snapshot in `inventory_db`.
4. Redis pre-warm adapter and Lua executor: implement `StockPreWarmPort` and
   execute the pre-warm resource as one typed atomic script.
5. Redis script configuration: loads the pre-warm Lua resource as a singleton
   typed script, consistent with the current decrement and projection-sync
   executors.

No Inventory REST endpoint, synchronous SaleService-to-InventoryService call,
new Kafka topic, new Redis key family, new table, cross-service database access,
or direct SaleService Redis stock write is required or permitted.

Kafka publishing/consumption and the SaleService scheduler are not implemented
by this documentation-only slice. The pre-warm feature cannot be described as
production-integrated until those already-architected adapters exist and are
verified.

## 16. Risks

- Kafka lag or scheduler outage can miss the T-60 target even though duplicate
  delivery protects against individual pod races.
- Redis `allkeys-lru` can evict either projection key after pre-warm; the active
  durable-authority path remains correct but may be slower.
- Independent key eviction creates partial state. Safe pre-start repair must be
  covered with real Redis tests.
- Clock skew between SaleService and InventoryService can produce early, late,
  or rejected triggers. The sale schedule remains authoritative and both
  services require observable clock health.
- A pre-warm timeout is indeterminate: Redis may already contain the pair even
  though the caller sees failure.
- Standalone Redis tests do not prove Redis Cluster routing. Same-hash-tag
  construction must remain explicit and cluster behavior remains a separate
  topology-verification risk.
- The current unintegrated Lua resource is incompatible with this decision and
  must not be wired unchanged.
- Older documents describe SaleService writing stock keys, querying
  `inventory_db`, using a synchronous internal endpoint, or relying on a
  `stock:warmed` marker. Those paths are superseded for pre-warm and can mislead
  later implementation unless documentation is reconciled after approval.
- Pre-sale rescheduling/cancellation semantics are not currently implemented;
  this ADR intentionally does not define how an already-established expiration
  changes if that future capability is added.

## 17. Alternatives considered

### Keep the existing `stock:warmed:{saleId}` marker script unchanged

Rejected. It creates revisionless stock, accepts caller-provided stock, adds a
third key, and is not safe against the active projection fence.

### Hard-code revision `0` during pre-warm

Rejected. The persisted StockLevel may already have a non-zero version. A
fabricated revision can be stale and is not an authoritative snapshot.

### Use SaleService `totalStock` or the trigger payload as stock authority

Rejected. It can disagree with InventoryService `current_stock` and violates
database and bounded-context ownership.

### Let SaleService execute the stock Lua script directly

Rejected. SaleService would become a writer of Inventory-owned keys and would
need stock/revision knowledge it does not own.

### Let InventoryService poll or query `sales_db`

Rejected. Cross-service database access is prohibited, and sale scheduling does
not belong in InventoryService.

### Add a synchronous internal pre-warm HTTP endpoint

Rejected. It introduces a new endpoint and synchronous state-change coupling
where the current platform architecture requires asynchronous service
notification.

### Use `SaleStarted` as the trigger

Rejected. It occurs at T0 and cannot satisfy the T-60 pre-warm target.

### Use creation-time `SaleScheduled` plus only an in-memory timer

Rejected. A pod restart after event acknowledgement loses the timer, and
multiple InventoryService pods need coordination. The durable SaleService
scheduler already owns time-based lifecycle evaluation.

### Reuse `StockProjectionSyncPort` to create missing keys

Rejected. Its approved contract intentionally leaves missing projections
missing and preserves an existing TTL. Making it create keys would blur
pre-warm and post-commit synchronization ownership.

### Add a distributed lock, leader election, or pre-warm database table

Rejected. Atomic Lua plus revision-fenced idempotency handles concurrent
execution without new infrastructure or durable coordination state.

## 18. Final decision

Adopt a SaleService-scheduled, asynchronously delivered, InventoryService-owned
pre-warm flow:

1. SaleService detects the T-60 window from its durable schedule and emits a
   pre-warm-due event over existing `sale-events`.
2. InventoryService validates the pre-start window, loads Product-owned
   `current_stock` and StockLevel `version` from one PostgreSQL snapshot, and
   derives expiration as `saleEnd + 10 minutes`.
3. InventoryService atomically initializes or revision-fences
   `stock:{saleId}` and `stock:version:{saleId}` with the same fixed expiration.
4. Duplicate equal-revision calls are no-ops, older revisions are ignored,
   newer revisions may update without extending TTL, and indeterminate calls
   are safely repeatable.
5. InventoryService exclusively owns both Redis keys. No warmed-marker key,
   caller-supplied stock, hard-coded revision, cross-service database access,
   synchronous endpoint, or new infrastructure is introduced.
6. A failed pre-warm is observable and degrades performance, not correctness;
   PostgreSQL remains authoritative and sale activation remains independent.

No implementation may begin until this ADR is reviewed and approved.

PRE-WARM ADR READY FOR REVIEW
