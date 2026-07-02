# KafkaDesign.md
## Flash Sale Platform — Kafka Reference
**Audience:** Interview preparation
**Source:** `docs/adr/01-Decisions.md` (ADR-006, ADR-007, ADR-008, ADR-017)
**Verified:** `apache/kafka:3.7.0` running · KRaft mode · `make health` exits 0

---

## Table of Contents

1. [Five core concepts](#1-five-core-concepts)
2. [Topic design](#2-topic-design)
3. [End-to-end flow](#3-end-to-end-flow)
4. [Partitions and the productId decision](#4-partitions-and-the-productid-decision)
5. [Consumer groups and offsets](#5-consumer-groups-and-offsets)
6. [Transactional Outbox](#6-transactional-outbox)
7. [Kafka in your docker-compose.yml](#7-kafka-in-your-docker-composeyml)
8. [Interview questions](#8-interview-questions)

---

## 1. Five Core Concepts

### Topic
A named, durable, ordered log of events. Unlike a queue, events are not deleted when consumed — they persist until the retention period expires. Multiple consumer groups can independently read every event at their own pace.

Your three topics:

| Topic | Partitions | Key | Retention | Producer |
|---|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days | SaleService |
| `inventory-events` | 16 | `productId` | 3 days | InventoryService |
| `order-events` | 8 | `saleId` | 3 days | OrderService (via outbox) |
| `notifications.dlq` | 4 | none | 14 days | NotificationService |
| `analytics.dlq` | 4 | none | 14 days | AnalyticsService |

### Producer
A service that writes events to a topic. Every event has a key. The key determines which partition the event goes to — via `CRC16(key) % partitionCount`. Events with the same key always go to the same partition, guaranteeing ordering for that key.

### Consumer
A service that reads events. Organised into consumer groups. Each consumer group independently tracks its own offset per partition. One consumer group reading does not affect another.

| Consumer group | Reads from | Service |
|---|---|---|
| `order-svc-inventory-consumer` | `inventory-events` | OrderService |
| `notification-svc-consumer` | all three topics | NotificationService |
| `analytics-svc-consumer` | all three topics | AnalyticsService |

### Partition
A topic is split into N ordered sub-logs. `inventory-events` has 16. Each partition is processed by exactly one consumer instance within a group. Ordering is guaranteed within a partition — not across partitions. This is why the partition key choice matters.

### Offset
A consumer group's position in a partition — a bookmark. Each group maintains its own offset independently. If OrderService is at offset 400 and NotificationService is at offset 350 on the same partition, they are 50 messages apart. Neither affects the other. Messages at offsets < 350 are readable by any new consumer group — they persist until the retention period ends.

---

## 2. Topic Design

### `sale-events` — 8 partitions, key=`saleId`

Produced by SaleService on every state transition: `SaleScheduled`, `SaleStarted`, `SaleEnded`, `SaleArchived`.

Consumed by NotificationService (notifies users a sale is starting) and AnalyticsService. `saleId` as key means all events for one sale go to the same partition — sale lifecycle is processed in order.

### `inventory-events` — 16 partitions, key=`productId`

Produced by InventoryService on every successful reservation: `StockReserved`.

16 partitions — double the other topics. This is the highest-throughput topic in the system. Every reservation produces an event here. At 50,000 concurrent reservations, this topic sees the most load.

`productId` as key: critical. See §4 for the full reasoning.

### `order-events` — 8 partitions, key=`saleId`

Produced by OrderService via the Transactional Outbox. Events: `OrderCreated`, `OrderConfirmed`, `OrderFailed`.

`saleId` as key: per-sale event ordering. All Order events for one sale go to the same partition, ensuring NotificationService and AnalyticsService see them in sale order.

### DLQ topics

`notifications.dlq` and `analytics.dlq` — 14-day retention. Ops alert fires when depth > 100 messages. Failed events are replayed manually after diagnosis.

---

## 3. End-to-End Flow

### Synchronous path — user waits

```
User        POST /reservations + Idempotency-Key
              ↓ HTTP
InventoryService
  ① Lua DECR on Redis → 201 Created (returned to user)
  ② Publish StockReserved to inventory-events (after ①)
```

Step ① and step ② are sequential inside InventoryService. The user gets `201 Created` and disconnects. The Kafka publish in step ② happens in the same thread but after the HTTP response has been sent. Everything below is asynchronous.

### Asynchronous path — client already has 201

```
inventory-events (16p, key=productId)
  ↓ order-svc-inventory-consumer
OrderService
  writes Order + OutboxEvent in @Transactional
  outbox poller polls every 500ms → publishes OrderCreated to order-events
  ↓ notification-svc-consumer
NotificationService → sends confirmation email
  ↓ analytics-svc-consumer
AnalyticsService → writes to ClickHouse
```

NotificationService also directly consumes `inventory-events` (to notify about reservation status) and `sale-events` (to notify about sale start). The diagram above shows the primary chain for simplicity.

### What decoupling means in practice

| Scenario | Impact on reservation |
|---|---|
| NotificationService is down for 10 minutes | Zero — buyer still has reservation, email arrives late |
| AnalyticsService is down for 1 hour | Zero — data replayed from offset when it recovers |
| OrderService is slow (consumer lag growing) | Zero on reservation response — lag is invisible to the buyer |
| Kafka is down for 5 minutes | Reservations degrade to Postgres fallback path; events accumulate in outbox table |

---

## 4. Partitions and the `productId` Decision

### Why this matters

Two concurrent users click Buy Now for the same product. InventoryService processes both and publishes:

```
Event A: productId=iPhone14, reservationId=R1, qty=1
Event B: productId=iPhone14, reservationId=R2, qty=1
```

OrderService must confirm them in the order they were accepted. R1 first. R2 second. If only one unit remains, R1 gets it. If R2 is confirmed first, the stock state is wrong.

### What `saleId` as key would do

All events for `FLASH-001` hash to the same partition. Only one OrderService pod processes them. No parallelism problem — but no scaling benefit either. All 16 partitions collapse to 1 for a single active sale.

Worse: `saleId` is not the right granularity. Two different products in the same sale have no ordering dependency between them. They should be processed in parallel.

### What `productId` as key gives you

```
iPhone14    → CRC16("iPhone14")  % 16 = Partition 11
SamsungS24  → CRC16("SamsungS24") % 16 = Partition 5
```

All reservations for iPhone14 go to Partition 11 — processed by one pod in strict order.
All reservations for SamsungS24 go to Partition 5 — processed by a different pod simultaneously.

Per-product ordering guaranteed. Maximum parallelism across products. 16 pods all working simultaneously on different products during a flash sale.

### Why not `userId`

A user's reservations have no ordering dependency between them. A user reserving iPhone14 and then headphones — order doesn't matter. `userId` would scatter same-product events across partitions, breaking per-product ordering with no benefit.

### Why 16 partitions, not 8

`inventory-events` is the highest-throughput topic — every reservation produces one event. During a flash sale at 50,000 RPS, this topic needs the most consumer parallelism. 16 partitions = up to 16 concurrent OrderService consumer pods. The other topics (8 partitions) have lower throughput requirements.

---

## 5. Consumer Groups and Offsets

### Consumer group = independent read cursor through the log

```
inventory-events Partition 0:

Messages:  [0][1][2][3][4][5]···
                       ↑
                       order-svc at offset:4 (next: msg-4)
               ↑
               notification-svc at offset:2 (next: msg-2)
```

OrderService has consumed msgs 0–3. NotificationService has consumed msgs 0–1. The same messages. The same partition. Neither affects the other.

Messages 0 and 1 are NOT deleted — they stay in the log for 3 days. Any new consumer group can replay from offset 0 and see every event since the topic was created (up to the retention window).

### How consumer group membership works

A consumer group with N members distributes partitions among members. 16 partitions, 4 OrderService pods: each pod owns 4 partitions. If a pod dies, Kafka rebalances — the remaining 3 pods each take on additional partitions. If a new pod starts, partitions rebalance again.

The maximum useful consumer pod count = partition count. 16 partitions = 16 pods maximum. A 17th pod would receive no partitions and sit idle.

### `enable.auto.commit=false` — why offsets are committed manually

Your Kafka consumer config uses `enable.auto.commit=false`. This means: the consumer must explicitly commit its offset after processing a message successfully.

Auto-commit commits periodically on a timer. If the pod crashes after processing a message but before the timer fires, the offset is never committed. On restart, the message is reprocessed. With explicit commit, the offset is committed only after the processing transaction completes.

```java
// Manual offset commit — only after successful write to Postgres
@KafkaListener(topics = "inventory-events", groupId = "order-svc-inventory-consumer")
@Transactional
public void handleStockReserved(StockReservedEvent event, Acknowledgment ack) {
    orderService.process(event);   // write to Postgres
    ack.acknowledge();             // commit offset AFTER successful write
}
```

If `orderService.process()` throws, `ack.acknowledge()` is never called. The offset stays at the previous position. On restart, the message is retried.

---

## 6. Transactional Outbox

### The problem it solves

OrderService consumes `StockReserved` and must publish `OrderCreated`. Two operations on two different systems:

```java
// WRONG — if crash happens between these two lines:
orderRepository.save(order);        // Postgres committed
kafkaTemplate.send("order-events"); // never happens
// OrderCreated is permanently lost
```

### The solution — outbox table in the same database transaction

```java
@Transactional
public void processReservation(StockReservedEvent event) {
    Order order = Order.from(event);
    orderRepository.save(order);              // row 1 in orders_db

    OutboxEvent outbox = OutboxEvent.from(order);
    outboxRepository.save(outbox);            // row 2 in orders_db
}
// Kafka is NOT touched here. If anything fails, both rows roll back.
```

The outbox poller runs every 500ms:

```sql
SELECT * FROM order_outbox
WHERE published = false
ORDER BY created_at
FOR UPDATE SKIP LOCKED  -- concurrent-safe: two pods cannot pick the same row
LIMIT 100
```

It publishes each row to Kafka and marks `published = true`. If Kafka is down for an hour, `order_outbox` accumulates rows. When Kafka recovers, the poller drains the backlog. No event is permanently lost.

### `FOR UPDATE SKIP LOCKED` — why this matters for multiple pods

Without `SKIP LOCKED`, two OrderService pods racing the same rows would each pick the same 100 rows, both try to publish, and produce duplicate events. `SKIP LOCKED` tells Postgres: "if a row is already locked by another transaction, skip it and move on." Each pod gets a distinct set of rows. No duplicates. No blocking.

### At-least-once delivery — consumers must be idempotent

The outbox guarantees at-least-once delivery. The same `OrderCreated` event can arrive twice (network retry, pod restart after publish but before `published=true` is committed). Every consumer must handle duplicates. Your platform uses `eventId` deduplication at the consumer side.

---

## 7. Kafka in Your `docker-compose.yml`

### KRaft mode — no Zookeeper

```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_NODE_ID: 1
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
```

One process. Both broker and controller roles. No separate Zookeeper ensemble to operate, monitor, or back up. KRaft is stable in Kafka 3.x and is the strategic direction.

### Two listeners — critical for Docker networking

```yaml
KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
```

| Listener | Address | Used by |
|---|---|---|
| `INTERNAL` | `kafka:29092` | Containers inside `flash-sale-net` — uses Docker DNS |
| `EXTERNAL` | `localhost:9092` | Your laptop, IDE, `kafka-topics.sh` on host |
| `CONTROLLER` | `kafka:9093` | KRaft internal only — do not use directly |

Inside Docker: `localhost` refers to the container itself, not the broker. Services must use `kafka:29092`. Your IDE must use `localhost:9092`.

### `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`

Services create their own topics via `@Bean KafkaTopicConfig` on startup. A typo in a topic name fails loudly (topic not found) instead of silently creating a phantom topic with wrong partition count and replication factor.

### Producer config

```yaml
acks=all                     # wait for all in-sync replicas before ack
enable.idempotence=true      # deduplicate at the broker — no duplicate messages on retry
```

`acks=all` means no message is acknowledged until every in-sync replica has written it. Zero message loss on broker failover. In a single-broker dev setup, `acks=all` = `acks=1` (one broker, one replica). In production (3 brokers, RF=3), it waits for all three.

---

## 8. Interview Questions

**"Walk me through what happens to a Kafka message from publish to consumption."**
InventoryService calls `kafkaTemplate.send("inventory-events", productId, stockReservedEvent)`. Kafka computes `CRC16(productId) % 16` to select the partition. The message is appended to that partition's log with the next offset number. OrderService's consumer pod for that partition polls and receives it. After processing, it commits the offset with `ack.acknowledge()`. The message remains in the log — AnalyticsService and NotificationService consume it independently with their own offset pointers.

**"Why is `inventory-events` partitioned by `productId` and not `saleId`?"**
Per-product ordering. Two concurrent reservations for the same product must be processed sequentially by OrderService. `saleId` would potentially scatter same-product events across different partitions, allowing two OrderService pods to process them concurrently in wrong order. `productId` guarantees all events for iPhone14 go to Partition 11 and are processed by exactly one pod in strict arrival order.

**"What is the Transactional Outbox and why does OrderService need it?"**
OrderService must persist an Order AND publish an `OrderCreated` event. These are two different systems — if the process crashes between them, one succeeds and the other fails. The outbox writes both the Order row and an OutboxEvent row in one database transaction. The outbox poller publishes the event to Kafka separately. Kafka publish failure means the poller retries. Database failure means both rows roll back. Neither case produces an inconsistent state.

**"What does `FOR UPDATE SKIP LOCKED` do in the outbox poller?"**
Multiple OrderService pods run the poller simultaneously. Without `SKIP LOCKED`, two pods would lock the same 100 rows, both publish, producing duplicates. `SKIP LOCKED` causes a pod to silently skip any rows already locked by another transaction, receiving only uncontested rows. Each pod gets a distinct set. No duplicates. No blocking between pods.

**"What happens if NotificationService is down during a flash sale?"**
Nothing. The `notification-svc-consumer` group stops committing offsets. Its lag grows. The messages stay in the log (3-day retention). When NotificationService recovers, it resumes from its last committed offset and replays all the missed events. Buyers receive delayed emails. The reservation, order, and inventory paths are completely unaffected — they share no synchronous dependency with NotificationService.

**"What is consumer group lag and when is it a problem?"**
Lag = messages produced minus messages consumed = how far behind the consumer is. Low lag (0–10) is normal. High lag during a flash sale spike is expected and acceptable if it drains afterward. Permanent high lag signals a consumer is too slow or under-provisioned. `make kafka-lag` shows lag per partition per consumer group. An ops alert fires when lag on any critical consumer group exceeds a threshold.

**"Why does `enable.auto.commit=false` matter?"**
Auto-commit periodically commits offsets on a timer regardless of whether processing succeeded. A crash after processing but before the timer commits the offset means the message is reprocessed on restart — usually fine. A crash DURING processing means the offset is committed for a message that was never successfully processed — that message is permanently skipped. Manual commit (`ack.acknowledge()` after successful Postgres write) eliminates the permanently-skipped case.

---

*Source: ADR-006 (async fan-out only), ADR-007 (partition key strategy),*
*ADR-008 (Transactional Outbox), ADR-017 (Kafka KRaft).*
*All decisions in `docs/adr/01-Decisions.md`.*