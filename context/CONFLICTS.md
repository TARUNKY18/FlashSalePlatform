# CONFLICTS.md — Flash Sale Platform

This document is the authoritative log of unresolved inconsistencies found across the repository's documentation. It is referenced by `PROJECT.md` rather than duplicated into it, per `AI-CONTEXT.md`'s rule: *"If documentation conflicts, stop and ask."*

**No conflict in this document has been resolved.** Each entry states what the documents actually say, why the discrepancy matters, and what the `AI-CONTEXT.md` precedence order (`README.md` > `PRD-FlashSalePlatform.md` > `Final-Spec-Council.md` > ADRs (`01-Decisions.md`) > `DomainModel.md` > `DatabaseSchema.md` > `schema.sql`, with `RedisDesign.md` / `KafkaDesign.md` / `Build-Plan.md` unranked) *would suggest* — as a recommendation only. The actual **Decision** field for every entry is `Pending`, and only Tarun can close it.

**Categories used below:**
- **Documentation Error** — the documents disagree in a way that looks like drift, a stale update, or a typo, rather than a deliberate design fork.
- **Design Decision** — the documents reflect two genuinely different, plausible design choices that need a real decision (and likely a new/amended ADR).
- **Future Implementation** — not a contradiction between two stated facts, but a gap: something is designed/planned in one place with no corresponding ratification elsewhere, or not decided anywhere yet.

---

# CONFLICT-001
Status:
OPEN
Category:
Design Decision
Documents:
- Final-Spec-Council.md (§4, "Layer 2 — Rate Limiter")
- 01-Decisions.md (Decision 011, "Redis Architecture — Three-Layer Contract")
- RedisDesign.md (§2 "Key Structure", §5 "Layer 2 — Rate Limiter")
- README.md (§11 "Redis operations", key-inspection examples)

Conflict:
Final-Spec-Council.md (§4):
"Key: rate:{userId}:{window_minute}"

01-Decisions.md (Decision 011):
"Rate limiter | API Gateway | `rate:{userId}:{window}` | Sorted Set | 60s | Fail-open + audit log"

RedisDesign.md (§2, §5):
"Rate limit | `rate:{userId}:{saleId}` | Sorted Set | API Gateway | Sliding window per user/sale"
"Key: rate:{userId}:{saleId}"

README.md (§11):
"redis-cli -p 7001 -a redis_dev --no-auth-warning -c ZCARD "rate:user-uuid:sale-uuid""

Reason:
These are two different rate-limiting semantics, not just a naming difference. `rate:{userId}:{window_minute}` scopes a buyer's 10-req/min limit globally across every sale they touch in that minute. `rate:{userId}:{saleId}` scopes it per sale, so hammering Sale A would not affect the buyer's limit on Sale B. This changes actual buyer-facing behavior and downstream capacity planning — it is not cosmetic.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
By strict rank, README.md (rank 1) outranks RedisDesign.md (unranked) and 01-Decisions.md / Final-Spec-Council.md (ranks 3–4), which would favor `rate:{userId}:{saleId}`. However, README's occurrence is a diagnostic `redis-cli` example in a troubleshooting section, not a formal key-schema statement, while Final-Spec-Council.md and the ADR state the schema as part of the actual architecture. Mechanically applying rank here may not reflect real intent — flagged rather than resolved.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-002
Status:
OPEN
Category:
Documentation Error
Documents:
- Final-Spec-Council.md (§4, "Layer 3 — Session & Idempotency Cache")
- 01-Decisions.md (Decision 009, "Order Idempotency — Dual-Layer Key Check")
- PRD-FlashSalePlatform.md (FR-017, and separately EC-007)
- RedisDesign.md (§7, "Layer 4 — Idempotency Cache")
- Build-Plan.md (Week 5, task 5.3)

Conflict:
Final-Spec-Council.md (§4):
"Key (idempotency): idem:{idempotencyKey}"

01-Decisions.md (Decision 009):
"1. Redis (`idem:{key}`, TTL 24h) — fast path, in-memory"

PRD-FlashSalePlatform.md (FR-017):
"The system shall check for an existing idempotency key in Redis (`idem:{key}`) before processing."

PRD-FlashSalePlatform.md (EC-007 — in the same document as FR-017):
"Idempotency key lookup is scoped by `userId` in addition to the key value. Key schema: `idem:{userId}:{idempotencyKey}`. Collision between users is impossible with this scoping."

RedisDesign.md (§7):
"Key: idem:{userId}:{idempotencyKey}"

Build-Plan.md (task 5.3):
"`IdempotencyRecord` entity: Redis `idem:{userId}:{key}` check → Postgres `idempotency_keys` fallback"

Reason:
This is the most concerning conflict in the repository because the PRD contradicts **itself** — FR-017 and EC-007 are in the same document and specify different key schemas. FR-017's unscoped `idem:{key}` is exactly the schema EC-007 says is unsafe (it allows a theoretical collision between two users' UUIDs). If FR-017's version is implemented literally, the collision-prevention behavior EC-007 promises does not exist.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
Precedence cannot resolve this cleanly because the primary conflict is *within* the rank-2 document itself (FR-017 vs EC-007). Setting that internal conflict aside, every other document that touches this (RedisDesign.md, Build-Plan.md, and the PRD's own EC-007) agrees on the `userId`-scoped form, and only the older/higher-level statements (FR-017, Decision 009, Final-Spec-Council.md) retain the unscoped form — consistent with FR-017/Decision 009/Final-Spec-Council.md being the stale version that was never updated after EC-007 introduced the fix.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-003
Status:
OPEN
Category:
Documentation Error
Documents:
- 01-Decisions.md (Decision 016, "Redis Cluster — Topology, Persistence, and Eviction Policy")
- RedisDesign.md (§1, "Cluster Topology")

Conflict:
01-Decisions.md (Decision 016):
"Memory cap: 4 GB per shard in production; `maxmemory-samples 10` for LRU approximation accuracy"

RedisDesign.md (§1):
"Memory cap | 4 GB total (≈1.3 GB/shard) | Eviction kicks in before OOM"

Reason:
This is a 3x numeric discrepancy (4 GB × 3 shards = 12 GB cluster-wide under the ADR, vs. 4 GB cluster-wide under RedisDesign.md). Provisioning, capacity alerts (NFR-027's "Redis memory utilisation > 70%/85%"), and the eviction-timing math in RedisDesign.md §11 ("4 GB cap supports ~235 simultaneous active sales") all depend on which number is correct.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
01-Decisions.md is rank 4; RedisDesign.md is unranked. By the stated precedence order, the ADR's "4 GB per shard" figure would win. RedisDesign.md's own numbers are internally self-consistent (4 GB ÷ 3 ≈ 1.3 GB/shard), which suggests it was derived by treating the ADR's per-shard figure as a cluster total — a plausible transcription slip rather than a deliberate re-decision.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-004
Status:
OPEN
Category:
Documentation Error
Documents:
- README.md (§2 "What is running", §3 "Service ports at a glance")
- Build-Plan.md (Week 1, Objectives / Deliverables / Definition of Done)

Conflict:
README.md (§3):
"| Kafka UI | http://localhost:18080 |"

Build-Plan.md (Week 1):
"Kafka UI UI at `localhost:8080` accessible, 0 topics (topics created by services)" (Deliverables)
"[ ] Kafka UI accessible at localhost:8080" (Definition of Done)

Reason:
A wrong port in an onboarding/build document sends an engineer to the wrong URL during Week 1 setup, which is exactly the "significant debugging time" README.md's own opening warning tries to prevent.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
README.md is rank 1; Build-Plan.md is unranked. This is a clean case for mechanical precedence — both statements are plain operational facts with no "formal spec vs. incidental example" nuance. README.md's `18080` would win.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-005
Status:
OPEN
Category:
Documentation Error
Documents:
- PRD-FlashSalePlatform.md (FR-022)
- 01-Decisions.md (Decision 007, "Impact")
- KafkaDesign.md (§3 "Consumer Groups")
- Build-Plan.md (Week 6, task 6.3; Week 8 retry-metadata example)

Conflict:
PRD-FlashSalePlatform.md (FR-022):
"The system shall consume `inventory-events` (consumer group: `order-svc-reservation-consumer`) to confirm that a reservation exists and is valid before creating an order."

01-Decisions.md (Decision 007, Impact):
"Consumer group `order-svc-reservation-consumer` processes per-product events in strict order."

KafkaDesign.md (§3):
"`order-svc-inventory-consumer` | `inventory-events` | OrderService | 16 | Per-product"

Build-Plan.md / KafkaDesign.md retry example (referenced in Week 8 tasks):
"consumerGroup": "order-svc-inventory-consumer"

Reason:
Kafka consumer-group IDs are load-bearing identifiers — they determine offset tracking, partition assignment, and which lag metric an alert or dashboard is watching. If the implementation, the monitoring config, and the documentation disagree on the name, a correctly-alerting dashboard for one name would show nothing for a service actually registered under the other.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
PRD-FlashSalePlatform.md is rank 2 and 01-Decisions.md is rank 4; both agree on `order-svc-reservation-consumer`. KafkaDesign.md and Build-Plan.md are unranked and agree on `order-svc-inventory-consumer`. By precedence, `order-svc-reservation-consumer` would win.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-006
Status:
OPEN
Category:
Documentation Error
Documents:
- README.md (Introduction banner; §5 "Starting the stack"; §2 "What is running" — all internal to this single file)

Conflict:
README.md (Introduction):
"The stack has 17 containers. Understanding what they are before starting saves significant debugging time."

README.md (§5):
"This command starts all 17 containers in dependency order, waits for healthchecks to pass, and automatically runs `make health`."

README.md (§2, itemized):
"PostgreSQL — 3 separate databases" (3 containers)
"Redis Cluster — 6 nodes" + "bootstrapped by a 7th container (`flash-sale-redis-cluster-init`)" (7 containers)
"Kafka — single broker" (1 container)
"ClickHouse — analytics database" (1 container)
"Tooling UIs — Two browser-based UIs" (2 containers)
— itemized total: 3 + 7 + 1 + 1 + 2 = **14**, not 17.

Reason:
This is an internal contradiction inside the single highest-priority document in the repository. It cannot be resolved by the precedence order at all, since both numbers appear in the rank-1 source. Either 3 containers are missing from the §2 breakdown (meaning something in the stack is undocumented) or the "17" figure is simply wrong and should read "14." Either way, this needs a direct check against the running stack (`make ps` / `docker compose ps`), not a documentation-precedence decision.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
Not applicable — the conflict is intra-document. Recommend verifying against `make ps` output and correcting README.md directly rather than picking one existing number over the other.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-007
Status:
OPEN
Category:
Documentation Error
Documents:
- Final-Spec-Council.md (§3, "Kafka — Final Topic Design")
- 01-Decisions.md (Decision 017, topic ownership table)
- README.md (§10, "Kafka operations" — `make kafka-create-topics` table)

Conflict:
Final-Spec-Council.md (§3) — full topic table:
"| `sale-events` | 8 | `saleId` | 7 days | ... |
| `inventory-events` | 16 | `productId` | 3 days | ... |
| `order-events` | 8 | `saleId` | 3 days | ... |
| `notifications.dlq` | 4 | none | 14 days | ... |"
— no `analytics.dlq` row.

01-Decisions.md (Decision 017):
"| `analytics.dlq` | AnalyticsService | none | ops replay tool |"

README.md (§10):
"| `analytics.dlq` | 4 | Failed analytics ingestion |"

Reason:
Final-Spec-Council.md is explicitly labeled "FINAL" and "the definitive architecture specification," so an implementer treating its §3 table as the complete topic list would never create `analytics.dlq` — yet README's own `make kafka-create-topics` target does create it, and the ADR log assumes it exists (it even has documented alerting thresholds for it in KafkaDesign.md §6).

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
README.md (rank 1) and 01-Decisions.md (rank 4) both include `analytics.dlq`; only Final-Spec-Council.md (rank 3) omits it. By precedence, README.md's inclusion wins, and Final-Spec-Council.md §3's table should be treated as incomplete rather than authoritative-by-omission.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-008
Status:
OPEN
Category:
Future Implementation
Documents:
- KafkaDesign.md (§1 "Topic Inventory", §5 "Retry Strategy")
- Build-Plan.md (Week 8, "Retry, DLQ, and NotificationService")
- (absent from) Final-Spec-Council.md, 01-Decisions.md, README.md

Conflict:
KafkaDesign.md (§1):
"| `sale-events.retry` | SaleService | 8 | `saleId` | 1 day | 3 |
| `inventory-events.retry` | InventoryService | 16 | `productId` | 1 day | 3 |
| `order-events.retry` | OrderService | 8 | `saleId` | 1 day | 3 |"

Build-Plan.md (Week 8, task 8.3):
"Retry consumers on `*.retry` topics: check `nextAttemptAfter`, virtual thread `Thread.sleep()` for delay"

Final-Spec-Council.md §3, 01-Decisions.md Decision 007 / Decision 017, and README.md §10:
No `.retry` topic of any kind is listed in any of these three documents' topic tables.

Reason:
This isn't two documents disagreeing on a fact — it's a substantial architectural element (3 additional Kafka topics, a full retry/backoff scheme, and dedicated retry consumer groups) that exists in detail in an unranked design document and is actively scheduled to be built in Week 8, but has no standing in the "final" architecture spec or the ADR log. If Week 8 proceeds as planned, the implementation will diverge from Final-Spec-Council.md and 01-Decisions.md without those documents ever having approved it.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
Not applicable in the usual sense — the ranked documents are silent rather than contradicting KafkaDesign.md. This needs a forward decision (most likely a new ADR ratifying the retry-topic design) rather than a precedence pick between existing statements.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-009
Status:
OPEN
Category:
Design Decision
Documents:
- 01-Decisions.md (Decision 011, "Redis Architecture — Three-Layer Contract")
- RedisDesign.md (Table of Contents; §1–§8)

Conflict:
01-Decisions.md (Decision 011):
"Three purpose-built Redis layers with distinct contracts: [Layer 1 Stock counter / Layer 2 Rate limiter / Layer 3 Session & Idempotency (combined)]"

RedisDesign.md (Table of Contents):
"4. Layer 1 — Stock Counter + Lua Scripts
5. Layer 2 — Rate Limiter
6. Layer 3 — Session Cache
7. Layer 4 — Idempotency Cache
8. Layer 5 — Sale Metadata Cache"

RedisDesign.md (§8, on the 5th layer):
"This layer was implicit in the spec (SaleService Redis cache) but not explicitly named. It deserves a formal definition because it is the hot path for `GET /api/v1/sales/{id}/active`."

Reason:
RedisDesign.md restructures the ADR's ratified 3-layer model into 5 layers by splitting Session and Idempotency into independent layers (each then getting its own, different key schema — see CONFLICT-002) and adding an entirely new "Sale Metadata" layer. This is the structural source of CONFLICT-001 and CONFLICT-002 above: once Session and Idempotency became separate layers, their key patterns drifted independently from the ADR's combined-layer schema.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
01-Decisions.md (rank 4) is the ratified layer count; RedisDesign.md is unranked, so the 3-layer model would formally win. That said, RedisDesign.md's own justification for Layer 5 (Sale Metadata) points to real support in Final-Spec-Council.md's service table, which already describes SaleService's Redis role as "Cache: active sale metadata" — suggesting this elaboration may deserve to be ratified into a new/amended ADR rather than simply discarded.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

# CONFLICT-010
Status:
OPEN
Category:
Future Implementation
Documents:
- (absent from) all 11 repository documents
- Build-Plan.md (Week 9, task/deliverable — incidental example only)

Conflict:
No document defines an HTTP application port for any of the 5 services (SaleService, InventoryService, OrderService, NotificationService, AnalyticsService). Every port that is documented is an infrastructure port (Postgres 5432/5433/5434, Kafka 9092/29092/9093, ClickHouse 8123/9000, Redis 7001–7006, Kafka UI 18080, RedisInsight 18081).

The only incidental mention of an application port is in Build-Plan.md (Week 9):
"`curl localhost:8081/actuator/prometheus` shows `reservation_latency_seconds` histogram"
— this is not tied to a named service and reads as an illustrative example, not a real port assignment.

Reason:
Docker Compose service definitions, Kubernetes `Service`/`Ingress` manifests, and any local multi-service run will all need concrete ports before Week 2 (SaleService skeleton) can be written. This is currently undecided rather than contradicted.

Precedence Recommendation (per AI-CONTEXT.md ranking, not a resolution):
Not applicable — no ranked document addresses this at all. This needs a new decision (and probably a short addendum to README.md's "Service ports at a glance" table), not a precedence pick.

Decision:
Pending

Owner:
Tarun

Resolution:
(To be filled later)

---

## Summary Table

| ID | Category | Documents Involved | Precedence Recommendation |
|---|---|---|---|
| CONFLICT-001 | Design Decision | Final-Spec-Council.md, 01-Decisions.md, RedisDesign.md, README.md | Ambiguous — rank favors README but context favors the ADR/spec |
| CONFLICT-002 | Documentation Error | PRD-FlashSalePlatform.md (internal), Final-Spec-Council.md, 01-Decisions.md, RedisDesign.md, Build-Plan.md | Internal PRD conflict — precedence can't fully resolve |
| CONFLICT-003 | Documentation Error | 01-Decisions.md, RedisDesign.md | 01-Decisions.md (rank 4) |
| CONFLICT-004 | Documentation Error | README.md, Build-Plan.md | README.md (rank 1) |
| CONFLICT-005 | Documentation Error | PRD-FlashSalePlatform.md, 01-Decisions.md, KafkaDesign.md, Build-Plan.md | PRD-FlashSalePlatform.md / 01-Decisions.md (ranks 2/4) |
| CONFLICT-006 | Documentation Error | README.md (internal) | N/A — intra-document, needs direct verification |
| CONFLICT-007 | Documentation Error | Final-Spec-Council.md, 01-Decisions.md, README.md | README.md / 01-Decisions.md (ranks 1/4) |
| CONFLICT-008 | Future Implementation | KafkaDesign.md, Build-Plan.md (vs. silence in ranked docs) | N/A — needs a new decision/ADR |
| CONFLICT-009 | Design Decision | 01-Decisions.md, RedisDesign.md | 01-Decisions.md (rank 4), but elaboration may merit ratification |
| CONFLICT-010 | Future Implementation | (absent everywhere) | N/A — undecided, not contradicted |

---

*Every "Decision" field above is `Pending` and every "Resolution" is unfilled. Nothing in this document has been acted upon. `PROJECT.md` should be treated as containing only what is verified and uncontested; anything listed here should be treated as open until Tarun closes it.*