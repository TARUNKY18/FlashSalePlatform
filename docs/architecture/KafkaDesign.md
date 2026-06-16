# KafkaDesign.md
## Flash Sale Platform — Kafka Architecture
**Version:** 1.0 | **Status:** Final
**Date:** 2026-06-15
**Source:** Final-Spec-Council.md v2.0
**Cardinal rule:** Kafka is async fan-out only. Never synchronous RPC.
                   A service needing an immediate answer calls an HTTP endpoint.
                   A service notifying others of a state change publishes an event.

---

## Table of Contents

1. [Topic Inventory](#1-topic-inventory)
2. [Partition Design](#2-partition-design)
3. [Consumer Groups](#3-consumer-groups)
4. [Event Schemas](#4-event-schemas)
5. [Retry Strategy](#5-retry-strategy)
6. [DLQ Strategy](#6-dlq-strategy)
7. [Producer Configuration](#7-producer-configuration)
8. [Consumer Configuration](#8-consumer-configuration)
9. [Spring Boot Implementation](#9-spring-boot-implementation)
10. [Operational Runbook](#10-operational-runbook)

---

## 1. Topic Inventory

### Topic catalogue

| Topic | Owner | Partitions | Key | Retention | Replication |
|---|---|---|---|---|---|
| `sale-events` | SaleService | 8 | `saleId` | 7 days | 3 |
| `inventory-events` | InventoryService | 16 | `productId` | 3 days | 3 |
| `order-events` | OrderService | 8 | `saleId` | 3 days | 3 |
| `sale-events.retry` | SaleService | 8 | `saleId` | 1 day | 3 |
| `inventory-events.retry` | InventoryService | 16 | `productId` | 1 day | 3 |
| `order-events.retry` | OrderService | 8 | `saleId` | 1 day | 3 |
| `notifications.dlq` | NotificationService | 4 | none | 14 days | 3 |
| `analytics.dlq` | AnalyticsService | 4 | none | 14 days | 3 |

### Producer → Topic → Consumer map

```
SaleService ──────────────► sale-events ──────────────────► NotificationService
                                          └────────────────► AnalyticsService

InventoryService ─────────► inventory-events ─────────────► OrderService
                                               ├────────────► NotificationService
                                               └────────────► AnalyticsService

OrderService ─────────────► order-events ─────────────────► NotificationService
                                           └────────────────► AnalyticsService

         ┌─ sale-events.retry ──────────────────────────────► NotificationService
         ├─ inventory-events.retry ────────────────────────► OrderService
         └─ order-events.retry ───────────────────────────► NotificationService
```

### Topic naming convention

```
{domain}-events              Main topic
{domain}-events.retry        Retry topic (delayed reprocessing)
{domain}.dlq                 Dead-letter queue (terminal failures)
```

---

## 2. Partition Design

### Partition count rationale

**`inventory-events` — 16 partitions (double the others)**

Inventory events are the highest-throughput topic. At 50,000 concurrent reservation
requests, each producing one `StockReserved` event, the producer burst is 50k msg/sec.
With 16 partitions and 3 OrderService consumer replicas (max 16 consumers possible),
each partition handles ~3,125 msg/sec at peak — well within Kafka broker limits.

More importantly: `inventory-events` is keyed by `productId`, not `saleId`. A flash
sale for one product generates all events on the same partition(s) that hash to that
`productId`. 16 partitions allows 16 products to be processed concurrently in isolation.

**`sale-events` and `order-events` — 8 partitions**

Lower throughput. `SaleStarted` fires once per sale; `OrderCreated` fires at the rate
of confirmed orders, which is throttled by the reservation TTL and saga steps. 8
partitions supports up to 8 parallel consumer instances per consumer group.

**`*.dlq` topics — 4 partitions**

DLQ topics are low-volume by design (high volume means something is wrong). 4
partitions is sufficient and keeps the DLQ observable without operational complexity.

### Partition key decisions

**Why `inventory-events` uses `productId`, not `saleId`:**

```
Scenario: 2 concurrent reservations for productId=P1 in saleId=S1

With saleId key:
  Reservation 1 → hash(S1) → partition 3
  Reservation 2 → hash(S1) → partition 3   ← same partition, ordered ✓
  But: all events for ALL products in S1 land on partition 3
       → OrderService consumer cannot parallelise per-product

With productId key:
  Reservation 1 → hash(P1) → partition 7
  Reservation 2 → hash(P1) → partition 7   ← same partition, ordered ✓
  Product P2 events → hash(P2) → partition 11  ← independent partition ✓
  → OrderService can parallelise across products within the same sale
```

Per-product ordering is the invariant that must be preserved. Two `StockReserved`
events for the same product must be processed sequentially to correctly confirm
reservations in order. `saleId` keying would preserve per-sale ordering but lose
per-product ordering when multiple products exist in one sale.

**Why `sale-events` and `order-events` use `saleId`:**

The saga for a given sale must process events in order. `SaleStarted` must be
processed before any `OrderCreated` that references that sale. Keying by `saleId`
puts all saga events for a sale on the same partition, guaranteeing ordering
without requiring the consumer to sort across partitions.

### Partition assignment strategy

```
Producer key → Kafka default partitioner (murmur2 hash)
partition = murmur2(key) % numPartitions

Example for inventory-events (16 partitions):
  productId = "3f7a..." → hash → partition 11
  productId = "9b2c..." → hash → partition 4
  productId = "ae51..." → hash → partition 11  ← collision, serialised
```

Hot partition risk: if a single product generates the vast majority of reservations,
all its events land on one partition. Mitigation: InventoryService monitors partition
lag per partition; alert if any single partition's lag exceeds 10,000 messages.

---

## 3. Consumer Groups

### Consumer group catalogue

| Consumer Group | Topic(s) Consumed | Service | Max Instances | Ordering Guarantee |
|---|---|---|---|---|
| `order-svc-inventory-consumer` | `inventory-events` | OrderService | 16 | Per-product |
| `notification-svc-consumer` | `sale-events`, `inventory-events`, `order-events` | NotificationService | 8 | Per-sale / per-product |
| `analytics-svc-consumer` | `sale-events`, `inventory-events`, `order-events` | AnalyticsService | 8 | Per-sale / per-product |
| `order-svc-retry-consumer` | `inventory-events.retry` | OrderService | 16 | Per-product |
| `notification-svc-retry-consumer` | `sale-events.retry`, `order-events.retry` | NotificationService | 8 | Per-sale |

### Consumer group design principles

**One consumer group per (service, purpose) pair.**
OrderService consumes `inventory-events` for saga processing and `inventory-events.retry`
for retry processing. These are separate consumer groups with separate offsets —
a failure in the retry consumer does not affect the main consumer's offset.

**Max instances = partition count.**
The maximum useful parallelism for a consumer group is the number of partitions.
Adding a 17th OrderService pod for `inventory-events` (16 partitions) produces an
idle consumer. HPA max replicas are set to match partition counts.

**Notification and Analytics consume all three topics.**
Each service has a single consumer group that subscribes to all three topics. This
is operationally simpler than three separate consumer groups per service and produces
the same throughput — Kafka assigns partitions from all subscribed topics to the
available consumer instances.

### Offset commit strategy

```
enable.auto.commit = false    ← mandatory for all consumers

Commit only after:
  1. Message is fully processed (DB write, HTTP call, etc.)
  2. No uncommitted messages in the current batch

For OrderService (saga-critical):
  Commit after each message individually — no batch commit
  Reason: a batch commit that succeeds for messages 1-4 but the pod
          crashes before message 5 causes messages 1-4 to be reprocessed.
          Individual commits bound reprocessing to a single message.

For NotificationService and AnalyticsService (non-critical path):
  Batch commit every 100 messages or 500ms, whichever first
  Reason: these consumers are idempotent (eventId deduplication);
          reprocessing a batch is safe and batch commits reduce broker load.
```

---

## 4. Event Schemas

### Envelope contract

Every event shares the same envelope. The `payload` field carries event-specific data.
Consumers must ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES = false`).

```json
{
  "eventId":       "uuid-v4",           // Deduplication key — UNIQUE per event
  "eventType":     "StockReserved",     // Discriminator for consumer routing
  "eventVersion":  "1.0",              // Semver: minor = backward-compatible add
  "occurredAt":    "2026-06-15T10:30:00.000Z",  // ISO-8601 UTC
  "aggregateId":   "uuid-v4",           // ID of the root aggregate
  "aggregateType": "Reservation",       // Aggregate type name
  "traceId":       "uuid-v4",           // Distributed trace propagation
  "payload":       { }                  // Event-specific fields below
}
```

**Versioning contract:**
- `1.0 → 1.1` — added optional field. Old consumers ignore it. Safe.
- `1.0 → 2.0` — breaking change (renamed/removed field). New `eventType` required.
- Consumers route on `eventType`, not `eventVersion`. Both `OrderCreated.1.0` and
  `OrderCreated.1.1` are handled by the same consumer method.

---

### sale-events schemas

**SaleScheduled**
```json
{
  "eventId":       "550e8400-e29b-41d4-a716-446655440000",
  "eventType":     "SaleScheduled",
  "eventVersion":  "1.0",
  "occurredAt":    "2026-06-15T10:00:00.000Z",
  "aggregateId":   "sale-uuid",
  "aggregateType": "FlashSale",
  "traceId":       "trace-uuid",
  "payload": {
    "saleId":       "sale-uuid",
    "name":         "Summer Flash Sale",
    "productId":    "product-uuid",
    "totalStock":   1000,
    "saleStart":    "2026-06-15T12:00:00.000Z",
    "saleEnd":      "2026-06-15T14:00:00.000Z",
    "timezone":     "UTC"
  }
}
```

**SaleStarted**
```json
{
  "eventType": "SaleStarted",
  "eventVersion": "1.0",
  "aggregateId": "sale-uuid",
  "aggregateType": "FlashSale",
  "payload": {
    "saleId":        "sale-uuid",
    "productId":     "product-uuid",
    "totalStock":    1000,
    "activatedAt":   "2026-06-15T12:00:00.000Z"
  }
}
```

**SaleEnded**
```json
{
  "eventType": "SaleEnded",
  "eventVersion": "1.0",
  "aggregateId": "sale-uuid",
  "aggregateType": "FlashSale",
  "payload": {
    "saleId":        "sale-uuid",
    "productId":     "product-uuid",
    "endedAt":       "2026-06-15T13:47:22.000Z",
    "endReason":     "STOCK_DEPLETED",
    "totalReserved": 1000,
    "totalOrders":   987
  }
}
```

---

### inventory-events schemas

**StockReserved** ← highest-volume event in the system
```json
{
  "eventType":     "StockReserved",
  "eventVersion":  "1.0",
  "aggregateId":   "reservation-uuid",
  "aggregateType": "Reservation",
  "payload": {
    "reservationId":  "reservation-uuid",
    "saleId":         "sale-uuid",
    "productId":      "product-uuid",
    "userId":         "user-uuid",
    "quantity":       1,
    "remainingStock": 142,
    "expiresAt":      "2026-06-15T12:10:00.000Z",
    "source":         "REDIS"
  }
}
```

**ReservationConfirmed**
```json
{
  "eventType": "ReservationConfirmed",
  "eventVersion": "1.0",
  "aggregateId": "reservation-uuid",
  "aggregateType": "Reservation",
  "payload": {
    "reservationId": "reservation-uuid",
    "orderId":       "order-uuid",
    "userId":        "user-uuid",
    "saleId":        "sale-uuid",
    "productId":     "product-uuid",
    "quantity":      1,
    "confirmedAt":   "2026-06-15T12:01:15.000Z"
  }
}
```

**ReservationExpired**
```json
{
  "eventType": "ReservationExpired",
  "eventVersion": "1.0",
  "aggregateId": "reservation-uuid",
  "aggregateType": "Reservation",
  "payload": {
    "reservationId": "reservation-uuid",
    "userId":        "user-uuid",
    "saleId":        "sale-uuid",
    "productId":     "product-uuid",
    "quantity":      1,
    "expiredAt":     "2026-06-15T12:10:00.000Z",
    "stockRestored": true
  }
}
```

**ReservationReleased** ← saga compensation event
```json
{
  "eventType": "ReservationReleased",
  "eventVersion": "1.0",
  "aggregateId": "reservation-uuid",
  "aggregateType": "Reservation",
  "payload": {
    "reservationId": "reservation-uuid",
    "orderId":       "order-uuid",
    "userId":        "user-uuid",
    "saleId":        "sale-uuid",
    "productId":     "product-uuid",
    "quantity":      1,
    "releaseReason": "SAGA_COMPENSATION",
    "releasedAt":    "2026-06-15T12:02:00.000Z"
  }
}
```

**StockAllocated**
```json
{
  "eventType": "StockAllocated",
  "eventVersion": "1.0",
  "aggregateId": "product-uuid",
  "aggregateType": "Product",
  "payload": {
    "productId":      "product-uuid",
    "saleId":         "sale-uuid",
    "totalAllocated": 1000,
    "allocatedAt":    "2026-06-15T11:59:00.000Z"
  }
}
```

---

### order-events schemas

**OrderCreated**
```json
{
  "eventType":     "OrderCreated",
  "eventVersion":  "1.0",
  "aggregateId":   "order-uuid",
  "aggregateType": "Order",
  "payload": {
    "orderId":        "order-uuid",
    "userId":         "user-uuid",
    "saleId":         "sale-uuid",
    "reservationId":  "reservation-uuid",
    "amount":         "99.99",
    "currency":       "USD",
    "idempotencyKey": "client-uuid",
    "createdAt":      "2026-06-15T12:01:00.000Z"
  }
}
```

**OrderConfirmed**
```json
{
  "eventType": "OrderConfirmed",
  "eventVersion": "1.0",
  "aggregateId": "order-uuid",
  "aggregateType": "Order",
  "payload": {
    "orderId":      "order-uuid",
    "userId":       "user-uuid",
    "saleId":       "sale-uuid",
    "amount":       "99.99",
    "currency":     "USD",
    "confirmedAt":  "2026-06-15T12:01:45.000Z"
  }
}
```

**OrderCancelled**
```json
{
  "eventType": "OrderCancelled",
  "eventVersion": "1.0",
  "aggregateId": "order-uuid",
  "aggregateType": "Order",
  "payload": {
    "orderId":        "order-uuid",
    "userId":         "user-uuid",
    "saleId":         "sale-uuid",
    "reservationId":  "reservation-uuid",
    "cancelReason":   "PAYMENT_FAILED",
    "cancelledAt":    "2026-06-15T12:02:00.000Z"
  }
}
```

---

## 5. Retry Strategy

### Retry topology

```
Main topic → Consumer processes → SUCCESS: commit offset
                               → RETRIABLE error: publish to .retry topic
                               → TERMINAL error: publish to .dlq topic

.retry topic → Consumer processes → SUCCESS: commit offset
                                  → TERMINAL after N attempts: publish to .dlq

.dlq → Ops team alert → Manual replay or discard
```

### Error classification

```
RETRIABLE errors (publish to .retry topic):
  - Downstream service temporarily unavailable (HTTP 5xx, connection timeout)
  - Database transient failure (connection pool exhausted, lock timeout)
  - Redis connection failure
  - Kafka producer transient error (leader not available, retriable)
  - External notification provider rate limit (429)

TERMINAL errors (publish to .dlq immediately, do not retry):
  - Deserialization failure (malformed JSON — retrying won't fix it)
  - Schema version mismatch (unknown eventType — retrying won't fix it)
  - Business rule violation (reservation already confirmed — idempotency issue)
  - Data integrity violation (foreign key missing — data problem, not transient)
  - Max retry attempts exhausted on .retry topic
```

### Per-topic retry configuration

```
sale-events consumers:
  Max attempts on main topic:   1 (no inline retry — publish to .retry immediately)
  Max attempts on .retry topic: 3
  Backoff schedule:             1s, 8s, 32s (exponential: base^attempt, base=4)
  Total window:                 ~41 seconds before DLQ

inventory-events consumers:
  Max attempts on main topic:   1
  Max attempts on .retry topic: 5
  Backoff schedule:             1s, 4s, 16s, 64s, 256s
  Total window:                 ~341 seconds (~5.7 min) before DLQ
  Reason: OrderService saga confirmation is time-sensitive but not instantaneous.
          Reservation TTL is 10 minutes — 5.7 minutes of retries leaves margin.

order-events consumers:
  Max attempts on main topic:   1
  Max attempts on .retry topic: 3
  Backoff schedule:             1s, 8s, 32s
  Total window:                 ~41 seconds before DLQ
```

### Retry topic message format

The retry topic message wraps the original event with retry metadata:

```json
{
  "originalEvent": { /* original DomainEvent envelope */ },
  "retryMetadata": {
    "attemptNumber":   2,
    "firstAttemptAt":  "2026-06-15T12:01:00.000Z",
    "lastAttemptAt":   "2026-06-15T12:01:08.000Z",
    "nextAttemptAfter":"2026-06-15T12:01:40.000Z",
    "errorType":       "RETRIABLE",
    "errorMessage":    "Connection refused: orders-db:5432",
    "consumerGroup":   "order-svc-inventory-consumer",
    "originalTopic":   "inventory-events",
    "originalPartition": 7,
    "originalOffset":  14923
  }
}
```

### Retry consumer delay implementation

Kafka has no native message delay. The retry consumer implements delay by:

1. Read message from `.retry` topic
2. Check `retryMetadata.nextAttemptAfter`
3. If `NOW < nextAttemptAfter`: sleep for the remaining duration, then process
4. If `NOW >= nextAttemptAfter`: process immediately

This is simple and avoids external scheduling infrastructure. The trade-off: a
retry consumer pod sleeps during the backoff window, consuming a thread. With
Java 21 virtual threads, this is negligible — sleeping virtual threads park and
release the platform thread.

Alternative (not chosen for v1): dedicated delay queue using Redis sorted sets
or a scheduler service. Adds operational overhead without meaningful benefit at
this scale.

---

## 6. DLQ Strategy

### DLQ topic design

```
Topic:           notifications.dlq
                 analytics.dlq
Partitions:      4
Retention:       14 days
Replication:     3
Compression:     lz4
Key:             original eventId (enables per-event deduplication on replay)
```

### DLQ message format

```json
{
  "dlqMetadata": {
    "dlqEventId":         "uuid-v4",
    "enqueuedAt":         "2026-06-15T12:05:00.000Z",
    "failureReason":      "TERMINAL",
    "errorType":          "DeserializationException",
    "errorMessage":       "Unrecognized field: 'newField' (eventVersion=2.0)",
    "consumerGroup":      "notification-svc-consumer",
    "originalTopic":      "order-events",
    "originalPartition":  3,
    "originalOffset":     88201,
    "attemptCount":       3,
    "firstAttemptAt":     "2026-06-15T12:01:00.000Z",
    "lastAttemptAt":      "2026-06-15T12:04:58.000Z"
  },
  "originalEvent": { /* original DomainEvent envelope — fully preserved */ }
}
```

### DLQ alerting thresholds

| Signal | Warning | Critical | Action |
|---|---|---|---|
| `notifications.dlq` depth | > 50 messages | > 100 messages | Page on-call |
| `analytics.dlq` depth | > 200 messages | > 500 messages | Slack alert (non-paging) |
| DLQ message age | > 1 hour | > 4 hours | Page on-call |
| DLQ ingestion rate | > 10 msg/min | > 50 msg/min | Page on-call + incident |

Analytics DLQ has higher thresholds because analytics lag is acceptable (target < 5s)
whereas notification failures directly impact buyer experience.

### DLQ replay procedure

```bash
# 1. Inspect DLQ contents
kafka-console-consumer \
  --bootstrap-server kafka:9092 \
  --topic notifications.dlq \
  --from-beginning \
  --max-messages 20

# 2. Filter for specific errorType
# (use custom replay tool — see KafkaDlqReplayTool)

# 3. Replay to original topic (after fixing root cause)
kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --group dlq-replay-tool \
  --topic notifications.dlq \
  --reset-offsets --to-earliest --execute

# 4. Replay tool reads DLQ, publishes originalEvent to originalTopic
# Key: originalEvent.eventId (idempotent — consumers deduplicate on eventId)
```

### DLQ replay tool (Spring Boot component)

```java
@Component
public class DlqReplayTool {

    private final KafkaTemplate<String, String> kafkaTemplate;

    // Admin endpoint: POST /admin/dlq/replay
    public void replay(String dlqTopic, ReplayFilter filter) {
        ConsumerRecords<String, String> records = pollDlq(dlqTopic, filter);
        for (ConsumerRecord<String, String> record : records) {
            DlqMessage dlqMessage = parse(record);
            String originalTopic     = dlqMessage.dlqMetadata().originalTopic();
            String originalEventId   = dlqMessage.originalEvent().eventId();
            String originalKey       = dlqMessage.originalEvent().aggregateId();

            // Publish to original topic — consumers deduplicate on eventId
            kafkaTemplate.send(originalTopic, originalKey,
                               dlqMessage.originalEvent().toJson());

            log.info("Replayed eventId={} to topic={}", originalEventId, originalTopic);
        }
    }
}
```

---

## 7. Producer Configuration

### Base producer config (all services)

```yaml
spring:
  kafka:
    producer:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

      # Delivery guarantees
      acks: all                      # Wait for all in-sync replicas
      retries: 3                     # Broker-level retries (transient leader elections)
      retry-backoff-ms: 100

      # Exactly-once semantics at producer level
      enable-idempotence: true       # Prevents duplicate messages on retry
      max-in-flight-requests-per-connection: 5  # Requires idempotence=true

      # Batching (throughput vs latency trade-off)
      batch-size: 16384              # 16KB batch
      linger-ms: 5                   # Wait up to 5ms for batch to fill
      buffer-memory: 33554432        # 32MB buffer

      # Compression
      compression-type: lz4          # Fast compression, ~3x size reduction

      # Timeouts
      request-timeout-ms: 30000
      delivery-timeout-ms: 120000    # Must be >= linger + request timeout
```

### Transactional producer (OrderService outbox poller)

OrderService uses the Transactional Outbox pattern. The outbox poller publishes
to Kafka after reading from the `order_outbox` table. The producer is NOT wrapped
in a Kafka transaction — the Postgres transaction is the atomicity boundary.
The outbox pattern already guarantees at-least-once delivery; Kafka transactions
would add overhead without correctness benefit at this scale.

```yaml
# OrderService producer — outbox poller specific
spring:
  kafka:
    producer:
      acks: all
      enable-idempotence: true
      # No transactional-id — outbox pattern handles idempotency
      # Consumers deduplicate on eventId, not Kafka transaction semantics
```

### Per-topic producer settings

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic saleEventsTopic() {
        return TopicBuilder.name("sale-events")
            .partitions(8)
            .replicas(3)
            .config(TopicConfig.RETENTION_MS_CONFIG,
                    String.valueOf(Duration.ofDays(7).toMillis()))
            .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
            .build();
    }

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name("inventory-events")
            .partitions(16)
            .replicas(3)
            .config(TopicConfig.RETENTION_MS_CONFIG,
                    String.valueOf(Duration.ofDays(3).toMillis()))
            .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
            .build();
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("order-events")
            .partitions(8)
            .replicas(3)
            .config(TopicConfig.RETENTION_MS_CONFIG,
                    String.valueOf(Duration.ofDays(3).toMillis()))
            .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
            .build();
    }

    @Bean
    public NewTopic notificationsDlqTopic() {
        return TopicBuilder.name("notifications.dlq")
            .partitions(4)
            .replicas(3)
            .config(TopicConfig.RETENTION_MS_CONFIG,
                    String.valueOf(Duration.ofDays(14).toMillis()))
            .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .build();
    }
}
```

---

## 8. Consumer Configuration

### Base consumer config (all services)

```yaml
spring:
  kafka:
    consumer:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest    # Start from beginning on new consumer group
      enable-auto-commit: false      # Manual commit only — mandatory
      max-poll-records: 100          # Process up to 100 messages per poll
      fetch-min-bytes: 1
      fetch-max-wait-ms: 500
      session-timeout-ms: 30000
      heartbeat-interval-ms: 10000
      isolation-level: read-committed # Only read committed messages
```

### Per-service consumer group config

```yaml
# OrderService — saga-critical, individual commits
order-service:
  kafka:
    consumer:
      group-id: order-svc-inventory-consumer
      max-poll-records: 50           # Smaller batches for per-message commit
      max-poll-interval-ms: 300000   # 5 min — saga steps can be slow

# NotificationService — idempotent, batch commits acceptable
notification-service:
  kafka:
    consumer:
      group-id: notification-svc-consumer
      max-poll-records: 100
      max-poll-interval-ms: 60000

# AnalyticsService — high throughput, batch commits
analytics-service:
  kafka:
    consumer:
      group-id: analytics-svc-consumer
      max-poll-records: 500          # Larger batches for ClickHouse bulk insert
      fetch-min-bytes: 50000         # Wait for 50KB before returning (batching)
      fetch-max-wait-ms: 1000        # Or 1 second, whichever first
```

---

## 9. Spring Boot Implementation

### Domain event envelope (Java 21)

```java
// Shared library — used by all services
public record DomainEvent<T>(
    String  eventId,
    String  eventType,
    String  eventVersion,
    Instant occurredAt,
    String  aggregateId,
    String  aggregateType,
    String  traceId,
    T       payload
) {
    public static <T> DomainEvent<T> of(String type, String aggId,
                                         String aggType, T payload) {
        return new DomainEvent<>(
            UUID.randomUUID().toString(),
            type,
            "1.0",
            Instant.now(),
            aggId,
            aggType,
            MDC.get("traceId"),       // Propagate trace context
            payload
        );
    }
}
```

### Producer — InventoryService

```java
@Component
@RequiredArgsConstructor
public class InventoryEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishStockReserved(Reservation reservation, int remainingStock) {
        StockReservedPayload payload = new StockReservedPayload(
            reservation.getId().toString(),
            reservation.getSaleId().toString(),
            reservation.getProductId().toString(),
            reservation.getUserId().toString(),
            reservation.getQuantity().value(),
            remainingStock,
            reservation.getExpiresAt().expiresAt().toString(),
            "REDIS"
        );

        DomainEvent<StockReservedPayload> event =
            DomainEvent.of("StockReserved",
                           reservation.getId().toString(),
                           "Reservation",
                           payload);

        String json = serialize(event);

        // Key = productId for per-product ordering guarantee
        kafkaTemplate.send("inventory-events",
                           reservation.getProductId().toString(),
                           json)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish StockReserved eventId={} error={}",
                              event.eventId(), ex.getMessage());
                    // Note: Outbox pattern in OrderService handles this.
                    // InventoryService publishes directly — if Kafka is down,
                    // the reservation is still committed to Postgres.
                    // The stock_reservation_log table provides reconciliation.
                } else {
                    log.debug("Published StockReserved eventId={} partition={} offset={}",
                              event.eventId(),
                              result.getRecordMetadata().partition(),
                              result.getRecordMetadata().offset());
                }
            });
    }

    private String serialize(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new EventSerializationException(e); }
    }
}
```

### Consumer — OrderService (saga-critical)

```java
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {

    private final OrderCommandService orderCommandService;
    private final DlqPublisher dlqPublisher;
    private final RetryPublisher retryPublisher;

    @KafkaListener(
        topics             = "inventory-events",
        groupId            = "order-svc-inventory-consumer",
        containerFactory   = "orderServiceKafkaListenerFactory"
    )
    public void consume(ConsumerRecord<String, String> record,
                        Acknowledgment ack) {

        DomainEvent<?> event = null;

        try {
            event = deserialize(record.value());

            // Idempotency check — skip if already processed
            if (alreadyProcessed(event.eventId())) {
                log.info("Skipping duplicate eventId={}", event.eventId());
                ack.acknowledge();
                return;
            }

            // Route by eventType
            switch (event.eventType()) {
                case "StockReserved"        -> handleStockReserved(event);
                case "ReservationExpired"   -> handleReservationExpired(event);
                case "ReservationReleased"  -> handleReservationReleased(event);
                default -> log.warn("Unknown eventType={} — ignoring", event.eventType());
            }

            ack.acknowledge();   // Commit only on full success

        } catch (DeserializationException | UnknownEventTypeException e) {
            // TERMINAL: retrying won't fix deserialization failures
            log.error("Terminal error eventId={} error={}",
                      event != null ? event.eventId() : "unknown", e.getMessage());
            dlqPublisher.publish("notifications.dlq", record, e, 1);
            ack.acknowledge();   // Commit to avoid poison-pill loop

        } catch (TransientException e) {
            // RETRIABLE: publish to retry topic, do NOT commit offset
            // The retry topic consumer will reprocess with backoff
            retryPublisher.publish("inventory-events.retry", record, e, 1);
            ack.acknowledge();   // Commit here too — retry topic is the reprocessing path
        }
    }

    private void handleStockReserved(DomainEvent<?> event) {
        StockReservedPayload payload = cast(event.payload(), StockReservedPayload.class);
        // ACL translation: Reservation → PurchaseIntent
        PurchaseIntent intent = inventoryEventTranslator.translate(payload);
        orderCommandService.processReservationConfirmed(intent);
    }
}
```

### Consumer — NotificationService (with retry and DLQ)

```java
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationDispatcher dispatcher;
    private final RetryPublisher retryPublisher;
    private final DlqPublisher dlqPublisher;
    private final EventIdempotencyChecker idempotencyChecker;

    @KafkaListener(
        topics           = {"sale-events", "inventory-events", "order-events"},
        groupId          = "notification-svc-consumer",
        containerFactory = "notificationKafkaListenerFactory"
    )
    public void consume(List<ConsumerRecord<String, String>> records,
                        Acknowledgment ack) {

        for (ConsumerRecord<String, String> record : records) {
            DomainEvent<?> event = null;
            try {
                event = deserialize(record.value());

                // Skip duplicates (eventId deduplication)
                if (idempotencyChecker.isSeen(event.eventId())) {
                    continue;
                }

                NotificationPayload notification = buildNotification(event);
                if (notification != null) {
                    dispatcher.dispatch(notification);
                }
                idempotencyChecker.markSeen(event.eventId());

            } catch (ProviderRateLimitException e) {
                retryPublisher.publish(retryTopicFor(record.topic()), record, e,
                                       retryAttempt(record));
            } catch (ProviderUnavailableException e) {
                retryPublisher.publish(retryTopicFor(record.topic()), record, e,
                                       retryAttempt(record));
            } catch (Exception e) {
                // Terminal — publish to DLQ
                dlqPublisher.publish("notifications.dlq", record, e,
                                     retryAttempt(record));
            }
        }

        ack.acknowledge();  // Batch commit after all records processed
    }

    private String retryTopicFor(String topic) {
        return topic + ".retry";
    }
}
```

### Outbox poller — OrderService

```java
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500)  // Every 500ms
    @Transactional
    public void poll() {
        List<OutboxEvent> unpublished = outboxRepository.findUnpublished(100);
        if (unpublished.isEmpty()) return;

        List<CompletableFuture<SendResult<String, String>>> futures = unpublished.stream()
            .map(event -> kafkaTemplate.send(
                topicFor(event.eventType()),
                partitionKeyFor(event),
                event.payload()
            ))
            .toList();

        // Wait for all sends — fail fast on any error
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, SECONDS);
            // Mark all as published in a single batch UPDATE
            outboxRepository.markPublished(
                unpublished.stream().map(OutboxEvent::id).toList()
            );
        } catch (Exception e) {
            log.error("Outbox publish failed, will retry on next poll: {}", e.getMessage());
            outboxRepository.recordAttempt(
                unpublished.stream().map(OutboxEvent::id).toList(), e.getMessage()
            );
            // Do NOT re-throw — let the next scheduled poll retry
        }
    }

    private String topicFor(String eventType) {
        return switch (eventType) {
            case "OrderCreated", "OrderConfirmed", "OrderCancelled" -> "order-events";
            default -> throw new IllegalArgumentException("Unknown eventType: " + eventType);
        };
    }

    private String partitionKeyFor(OutboxEvent event) {
        // order-events partition key = saleId (from payload)
        return extractSaleId(event.payload());
    }
}
```

---

## 10. Operational Runbook

### Consumer lag monitoring

```bash
# Check lag for all consumer groups
kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --describe --all-groups

# Expected healthy output:
# GROUP                          TOPIC              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# order-svc-inventory-consumer   inventory-events   0          14923           14923           0
# order-svc-inventory-consumer   inventory-events   1          11204           11204           0
# ...

# Alert thresholds (from NFR-027):
# WARNING:  lag > 5,000 messages per partition
# CRITICAL: lag > 10,000 messages per partition
```

### Kafka health checks

All services expose consumer group lag via Spring Boot Actuator:
```
GET /actuator/kafka
GET /actuator/metrics/kafka.consumer.fetch-rate
GET /actuator/metrics/kafka.producer.record-send-rate
```

### Partition rebalance during sale (EC-016)

When a consumer pod is killed during a sale, Kafka triggers a consumer group
rebalance. During rebalance (10–30 seconds), consumption pauses. Events accumulate
in Kafka (all messages are persisted — none are lost). On recovery:

1. New partition assignment completes
2. Consumer resumes from last committed offset
3. Events are processed in order — no gaps, no duplicates (eventId deduplication)

**Pre-sale pre-scaling:** Scale consumer pods to max replicas 5 minutes before
`saleStart` to avoid rebalances during peak. This is a runbook step, not code.

```bash
# Pre-scale before sale
kubectl scale deployment notification-service --replicas=5
kubectl scale deployment analytics-service --replicas=5
kubectl scale deployment order-service --replicas=16  # Match inventory-events partition count
```

### DLQ investigation flow

```
1. Alert fires: notifications.dlq depth > 100
2. Inspect: kafka-console-consumer --topic notifications.dlq --from-beginning --max-messages 5
3. Identify errorType from dlqMetadata.errorType
4. If DESERIALIZATION: schema version mismatch — check producer eventVersion
5. If PROVIDER_UNAVAILABLE: external service down — fix provider, then replay
6. If BUSINESS_RULE: data integrity issue — investigate manually, discard if stale
7. Replay (if safe): POST /admin/dlq/replay {"topic":"notifications.dlq","filter":"errorType=PROVIDER_UNAVAILABLE"}
```

### Offset reset (emergency)

```bash
# DANGER: Only in recovery scenarios — reprocesses all events
kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --group order-svc-inventory-consumer \
  --topic inventory-events \
  --reset-offsets --to-earliest --execute

# Safer: reset to specific timestamp (e.g., 1 hour ago)
kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --group order-svc-inventory-consumer \
  --reset-offsets --to-datetime 2026-06-15T11:00:00.000 --execute
```

---

*Kafka design derived from Final-Spec-Council.md v2.0.*
*No cross-service synchronous calls. Every inter-service notification goes through Kafka.*
*Consumers are idempotent. The eventId field is the universal deduplication key.*
*Next: Spring Boot service skeletons with Kafka configuration wired up.*