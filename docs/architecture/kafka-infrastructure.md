# Kafka Infrastructure

**Date:** 2026-08-01
**Last updated:** 2026-08-01 (C3 — declarative topic provisioning)
**Status:** living — updated as topics, consumers, and patterns evolve
**Scope:** All services that produce to or consume from Kafka

> **Purpose.** This document is the single source of truth for the Kafka topology:
> what topics exist, who produces to them, who consumes from them, what patterns
> are in use, and where the gaps are. It answers "what runs on Kafka right now?"
> without requiring a grep across five compose files and six application.yml files.
>
> This is a **living reference doc** — update it when you add a topic, change a
> consumer group, introduce a new event type, or close a production gap.

---

## 1. Topology Diagram

```
                        ┌─────────────────────┐
                        │   stream-service     │
                        │                      │
                        │  OutboxWriter ───────┤  writes to stream.outbox (PG)
                        │  OutboxPoller ───────┤  polls DB → publishes to Kafka
                        │                      │
                        │  (legacy, superseded)│
                        │  StreamEventPublisher│  fire-and-forget, at-most-once
                        └──────────┬───────────┘
                                   │
                         produces to stream.control
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    │                    ▼
       stream.control              │             stream.control
              │                    │                    │
   ┌──────────┴──────────┐        │      ┌─────────────┴─────────────┐
   │    chat-service      │        │      │   notification-service    │
   │    group: chat-svc   │        │      │   group: notification-svc │
   │                      │        │      │                           │
   │ CREATED → open room  │        │      │ STARTED → fanout to subs  │
   │ ENDED → archive room │        │      │ ENDED → notify broadcaster│
   │ ARCHIVE → archive    │        │      │ CREATED → log (audit)     │
   │                      │        │      │ SCHEDULED → log (audit)   │
   │ Dedup: ✗ (DB layer)  │        │      │ CANCELLED → log (audit)  │
   │ DLQ: ✓ (3 retry)     │        │      │                           │
   │                      │        │      │ Dedup: ✓ (Redis SETNX)    │
   └──────────────────────┘        │      │ DLQ: ✓ (3 retry)          │
                                   │      └───────────────────────────┘
                                   │
                          stream.control.dlq
                          (dead letters from both consumers)
                          ⚠ No consumer — messages accumulate silently
```

---

## 2. Topic Catalog

| Topic | Partitions | RF | Retention | Provisioning | Purpose | Owner |
|-------|-----------|-----|-----------|-------------|---------|-------|
| `stream.control` | 1 | 1 | 7 days (broker default) | `KafkaTopicConfig.streamControlTopic()` in `common` | Stream lifecycle events: created, scheduled, started, ended, cancelled, chat-archive-triggered | stream-service (producer) |
| `stream.control.dlq` | 1 | 1 | 7 days (broker default) | `KafkaTopicConfig.streamControlDlqTopic()` in `common` | Dead letters from chat-service and notification-service consumers after 3 failed retries | stream-service (topology owner); chat/notification (publish to DLQ) |

> **Provisioning:** Topics are defined as `@Bean NewTopic` in
> `common/src/main/java/com/streaming/common/messaging/KafkaTopicConfig.java`.
> Spring Kafka's `KafkaAdmin` (auto-configured in stream-service) creates them idempotently
> at application startup. Broker auto-creation is disabled across all environments (C3).
> To add a topic: add a `@Bean` method in `KafkaTopicConfig`, restart stream-service.
> No broker restart required.

### Provisioning Strategy

**Where topic definitions live, why, and what alternatives were considered.**

#### Decision

Kafka topics are defined as `@Bean NewTopic` methods in the `common` module
(`common/src/main/java/com/streaming/common/messaging/KafkaTopicConfig.java`).
`KafkaAdmin` (auto-configured by Spring Boot in stream-service) creates them
idempotently at application startup via the AdminClient protocol — no broker
restart needed.

#### Why `common`?

The `common` module already holds `StreamEvent.java` — the canonical event
envelope shared by stream-service (producer), chat-service, and
notification-service (consumers). Topic definitions are the infrastructure
counterpart to that contract: the event schema says *what* flows; the topic
config says *where* it flows. Colocating them keeps the platform contract in
one place.

A service that depends on `common` gets:
- **Event schema** (`StreamEvent` record) — what the messages look like
- **Topic definitions** (`@Bean NewTopic`) — where they go
- **Topic name constants** (implicit via the bean method names) — no
  hardcoded strings to drift

Pure consumers (chat-service, notification-service) don't need `KafkaAdmin` —
they just need bootstrap servers and the topic name. Only the producer
(stream-service) actually runs the `KafkaAdmin` auto-configuration that calls
`createTopics()` at boot.

#### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| **`KAFKA_CREATE_TOPICS` env var** (broker-level) | Rejected for this stack | Requires broker restart for new topics; topic config lives in docker-compose, not near code; doesn't work when Kafka is remote (must edit remote broker config). Good for static production clusters; wrong fit for an evolving dev topology. |
| **`NewTopic` beans per service** (each producer defines its own) | Rejected | With only one producer today, this devolves to "put it in stream-service." But as the system grows, duplicating topic definitions across services invites partition/RF drift and ownership confusion. |
| **Infrastructure-as-Code** (Terraform/Pulumi) | Deferred | The right long-term answer for production. But requires a CI/CD pipeline, remote state, and an infra module that doesn't exist yet. The shared-library approach migrates cleanly: delete `KafkaTopicConfig.java`, point Terraform at the same topic names. |
| **`@Bean NewTopic` in `common`** (shared library) | **Chosen** | Single source of truth. No broker restart. Colocated with event schema. Zero duplication. Trivial to migrate to Terraform later. One downside: `common` now depends on `spring-kafka`, which transitively reaches all consumers — but every service except `auth-service` already depends on `spring-kafka` directly, so the net new dependency weight is zero. |

#### Migration Path to Infrastructure-as-Code

When the project adopts an infrastructure pipeline:

1. Extract topic definitions from `KafkaTopicConfig.java` into a Terraform
   `kafka_topics.tf` (or equivalent) using the same topic names, partitions,
   and replication factors
2. Remove `KafkaTopicConfig.java` and the `spring-kafka` dependency from `common`
3. Topics are created by the CI/CD pipeline before services deploy
4. Services only need: bootstrap servers + topic name

No application code changes. The topic names are the stable contract.

#### Consequences

- **Positive:** Adding a topic is a single `@Bean` method + app restart. No
  broker downtime.
- **Positive:** The `KafkaTopicConfig` class serves as a human-readable
  catalog — `grep "@Bean" KafkaTopicConfig.java` lists every topic in the
  system.
- **Negative:** `common` now carries `spring-kafka` as a dependency. Mitigated
  by the fact that 3 of 4 backend services already depend on it.
- **Negative:** Topic creation depends on stream-service being running. On a
  fresh Kafka instance without stream-service, topics won't exist. Mitigated
  by stream-service being a core part of the stack — it's always deployed
  alongside Kafka.

---

## 3. Event Type Catalog

All events flow through `stream.control`. The canonical event envelope is `com.streaming.common.messaging.StreamEvent`.

| Event Type | Factory Method | Published When | chat-service Reaction | notification-service Reaction |
|------------|---------------|----------------|----------------------|------------------------------|
| `STREAM_CREATED` | `StreamEvent.created()` | Stream entity persisted | Open/ensure chat room + "Stream started" system message | Log (audit) |
| `STREAM_SCHEDULED` | `StreamEvent.scheduled()` | Stream created with future `scheduledAt` | _(not handled)_ | Log (audit); reminders deferred |
| `STREAM_STARTED` | `StreamEvent.started()` | SRS webhook: stream is live | _(not handled)_ | Persist broadcaster notification + fanout to followers |
| `STREAM_ENDED` | `StreamEvent.ended()` | SRS webhook: stream ended, or admin ends | "Stream ended" system message; archive if `autoArchiveChat` + `delay=0` | Persist STREAM_ENDED notification for broadcaster |
| `STREAM_CANCELLED` | `StreamEvent.cancelled()` | Stream deleted | _(not handled)_ | Log (audit) |
| `CHAT_ARCHIVE_TRIGGERED` | `StreamEvent.chatArchiveTriggered()` | `ChatArchiveScheduler` finds ENDED stream with elapsed archive delay | Archive room, evict cache, system message | _(not handled)_ |

### Event Envelope Fields

```java
// com.streaming.common.messaging.StreamEvent
String  eventType            // one of the six types above
UUID    streamId             // the stream this event is about
UUID    eventId              // unique per event — used for consumer dedup
Instant timestamp            // when the event was created
String  broadcasterSubject   // auth subject of the broadcaster
String  broadcasterUsername  // display name (denormalized for consumers)
boolean autoArchiveChat      // whether chat should auto-archive on stream end
int     chatArchiveDelayMinutes // delay before archive (0 = immediate)
```

> **Security note:** Stream keys are NOT included in the event payload. Events may be consumed
> by services that should not see publish secrets (ADR-0002).

---

## 4. Producer Inventory

### 4.1 stream-service — OutboxPoller (primary)

| Field | Value |
|-------|-------|
| **Class** | `com.streaming.stream.messaging.OutboxPoller` |
| **File** | `main/source/backend/stream-service/src/main/java/com/streaming/stream/messaging/OutboxPoller.java` |
| **Pattern** | Transactional Outbox |
| **Delivery guarantee** | At-least-once (with consumer dedup as safeguard) |
| **Topic** | `stream.control` (via `${streaming.kafka.topic.stream-control}`) |
| **Schedule** | `@Scheduled(fixedDelay = 5000ms)` |
| **Batch size** | 50 (configurable via `streaming.outbox.batch-size`) |
| **Max retries** | 3 (configurable via `streaming.outbox.max-retries`) |
| **Publish timeout** | 5 seconds per event |
| **Concurrency** | Up to 16 concurrent publishes per poll cycle |
| **Locking** | `FOR UPDATE SKIP LOCKED` on `stream.outbox` |
| **Failure mode** | After 3 retries: deletes row, logs error. DLQ integration pending (Task A5). |
| **Serialization** | Jackson `ObjectMapper.writeValueAsString()` → `KafkaTemplate<String, String>` |

### 4.2 stream-service — OutboxWriter (transactional write)

| Field | Value |
|-------|-------|
| **Class** | `com.streaming.stream.service.OutboxWriter` |
| **File** | `main/source/backend/stream-service/src/main/java/com/streaming/stream/service/OutboxWriter.java` |
| **Pattern** | Transactional Outbox (write side) |
| **Behavior** | Serializes `StreamEvent` → inserts into `stream.outbox` within the same R2DBC transaction as the entity save |
| **Call sites** | All 6 lifecycle transitions in `StreamService` (created, scheduled, started, ended, cancelled) + `ChatArchiveScheduler` |

### 4.3 stream-service — StreamEventPublisher (legacy, superseded)

| Field | Value |
|-------|-------|
| **Class** | `com.streaming.stream.messaging.StreamEventPublisher` |
| **File** | `main/source/backend/stream-service/src/main/java/com/streaming/stream/messaging/StreamEventPublisher.java` |
| **Pattern** | Fire-and-forget |
| **Delivery guarantee** | At-most-once |
| **Status** | Still injected into `StreamService` but **no longer called** for lifecycle transitions. All writes now go through `OutboxWriter`. |
| **Transport** | `Mono.fromFuture(kafkaTemplate.send(...))` + `Schedulers.boundedElastic()` |

---

## 5. Consumer Inventory

### 5.1 chat-service — StreamControlListener

| Field | Value |
|-------|-------|
| **Class** | `com.streaming.chat.messaging.StreamControlListener` |
| **File** | `main/source/backend/chat-service/src/main/java/com/streaming/chat/messaging/StreamControlListener.java` |
| **Annotation** | `@KafkaListener(topics = "${STREAM_CONTROL_TOPIC:stream.control}", groupId = "${spring.kafka.consumer.group-id}")` |
| **Consumer group** | `chat-service` (hardcoded in `application.yml`) |
| **Ack mode** | Default (batch) |
| **Auto-startup** | `${KAFKA_LISTENER_ENABLED:true}` |
| **Handled events** | `STREAM_CREATED`, `STREAM_ENDED`, `CHAT_ARCHIVE_TRIGGERED` |
| **Unhandled events** | `STREAM_STARTED`, `STREAM_SCHEDULED`, `STREAM_CANCELLED` — logged and ignored |
| **Unknown events** | Logged as warning and skipped |
| **Processing timeout** | 10 seconds (`blockOptional(Duration.ofSeconds(10))`) |
| **Idempotency** | None at the messaging layer. Relies on idempotent DB operations (`getOrCreate` room, unique index on `client_id`). |
| **Event model** | Uses a **local** `StreamEvent` record subset (`com.streaming.chat.messaging.StreamEvent`) with `@JsonIgnoreProperties(ignoreUnknown = true)` — only extracts 5 fields |

### 5.2 notification-service — StreamControlListener

| Field | Value |
|-------|-------|
| **Class** | `com.streaming.notification.messaging.StreamControlListener` |
| **File** | `main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/StreamControlListener.java` |
| **Annotation** | `@KafkaListener(topics = "${STREAM_CONTROL_TOPIC:stream.control}", groupId = "${spring.kafka.consumer.group-id}")` |
| **Consumer group** | `${KAFKA_GROUP_NOTIFICATION:notification-service}` |
| **Ack mode** | `record` (commit after each individual record) |
| **Auto-startup** | `${KAFKA_LISTENER_ENABLED:true}` |
| **Handled events** | `STREAM_STARTED` (fanout + notification), `STREAM_ENDED` (notify broadcaster) |
| **Audit-only events** | `STREAM_CREATED`, `STREAM_SCHEDULED`, `STREAM_CANCELLED` — logged for audit |
| **Unknown events** | Logged as warning and skipped |
| **Processing timeout** | 10 seconds (`blockOptional(Duration.ofSeconds(10))`) |
| **Idempotency** | Redis `SETNX` with key `dedup:{topic}:{consumerGroupId}:{eventId}`, TTL 24h. If Redis unavailable: fail-open, process anyway. |

---

## 6. DLQ Configuration

Both consumers have **identical** DLQ setups defined in their `KafkaConsumerConfig`:

| Setting | Value |
|---------|-------|
| **DLQ topic** | `stream.control.dlq` (configurable via `streaming.kafka.dlq-topic`) |
| **Max retries** | 3 |
| **Backoff** | Fixed 1-second (`FixedBackOff(1000L, 3L)`) |
| **Non-retryable** | `SerializationException` (malformed message can never succeed) |
| **Recovery** | `DeadLetterPublishingRecoverer` → publishes original message to DLQ |
| **DLQ publisher** | Dedicated `KafkaTemplate<String, String>` bean (`dlqPublisher`) |

**Config files:**
- `main/source/backend/chat-service/src/main/java/com/streaming/chat/config/KafkaConsumerConfig.java`
- `main/source/backend/notification-service/src/main/java/com/streaming/notification/config/KafkaConsumerConfig.java`

> ⚠ **Gap:** No consumer reads from `stream.control.dlq`. Poison-pill messages accumulate silently
> with no alerting, no replay mechanism, and no dashboard visibility. This is a known gap
> deferred to the observability phase.

**stream-service OutboxPoller DLQ status:** The outbox poller has its own internal retry (max 3)
and deletes the row after exhaustion. DLQ integration is marked as "pending Task A5" in source
comments — outbox failures are not yet published to the DLQ.

---

## 7. Outbox Pattern Status

### 7.1 Kafka Outbox (stream-service)

| Aspect | Detail |
|--------|--------|
| **Table** | `stream.outbox` (Flyway migration `V13__create_outbox.sql`) |
| **Entity** | `com.streaming.stream.persistence.entity.OutboxEvent` |
| **Repository** | `com.streaming.stream.persistence.repository.OutboxEventRepository` |
| **Writer** | `com.streaming.stream.service.OutboxWriter` |
| **Poller** | `com.streaming.stream.messaging.OutboxPoller` |
| **Columns** | `id UUID PK`, `event_type VARCHAR(100)`, `stream_id UUID`, `payload JSONB`, `retry_count INT DEFAULT 0`, `created_at TIMESTAMPTZ`, `last_attempt_at TIMESTAMPTZ`, `published BOOLEAN DEFAULT false` |
| **Polling query** | `SELECT ... FROM stream.outbox WHERE published = false ORDER BY created_at, id LIMIT N FOR UPDATE SKIP LOCKED` |
| **Partial index** | `idx_outbox_unpublished ON stream.outbox (created_at, id) WHERE published = false` |
| **ADR** | [ADR-stream-0009](../adr/stream/0009-outbox-pattern.md) |

### 7.2 Delivery Outbox (notification-service — NOT Kafka)

| Aspect | Detail |
|--------|--------|
| **Table** | `notification.notification_outbox` (Flyway `V1__bootstrap_notification_schema.sql`, `V5__change_outbox_payload_to_text.sql`) |
| **Entity** | `com.streaming.notification.domain.OutboxEntry` |
| **Repository** | `com.streaming.notification.infrastructure.persistence.ReactiveOutboxRepository` |
| **Purpose** | Reliable email/push notification delivery — NOT Kafka message publishing |
| **States** | `PENDING`, `SENT`, `FAILED`, `DEAD` |

---

## 8. Saga / Choreography Map

There is no explicit saga orchestrator. The system uses **event choreography**:
each service reacts independently to `stream.control` events.

| Trigger Event | Downstream Action | Service | Compensation / Timeout |
|--------------|-------------------|---------|----------------------|
| `STREAM_CREATED` | Open/ensure chat room | chat-service | None — idempotent `getOrCreate` |
| `STREAM_CREATED` | Log for audit | notification-service | None |
| `STREAM_STARTED` | Fanout notification to followers | notification-service | None — fire-and-forget |
| `STREAM_ENDED` | Archive chat room (if `autoArchiveChat` + `delay=0`) | chat-service | None — idempotent archive |
| `STREAM_ENDED` | Notify broadcaster | notification-service | None |
| `CHAT_ARCHIVE_TRIGGERED` | Archive chat room + evict cache | chat-service | None — scheduled poller, idempotent |

> **No compensating transactions exist.** If a downstream action fails after 3 retries,
> the message goes to the DLQ and processing stops. There is no rollback of the source
> event or notification to other services. This is acceptable for Phase 2.0 but would
> need saga patterns (compensation, orchestration) for production at scale.

---

## 9. Serialization

| Aspect | Current State | Future Direction |
|--------|--------------|-----------------|
| **Format** | JSON via Jackson `ObjectMapper` | Avro or JSON Schema (deferred, K4) |
| **Key serializer** | `StringSerializer` (streamId) | Same |
| **Value serializer** | `StringSerializer` (JSON string) | `KafkaAvroSerializer` if schema registry adopted |
| **Schema registry** | None | Apicurio Registry (open-source) evaluated over Confluent Schema Registry |
| **Schema evolution** | Ad-hoc — chat-service uses `@JsonIgnoreProperties(ignoreUnknown = true)` as defensive measure | Compatibility modes: BACKWARD / FORWARD / FULL |
| **Breaking change risk** | Adding a required field to `StreamEvent` breaks chat-service's local subset deserialization if `ignoreUnknown` is ever removed | Schema registry would make this explicit |

---

## 10. Configuration Reference

### Cluster Topology

| Config | File | Brokers | Port |
|--------|------|---------|------|
| Root dev (single-broker KRaft) | `compose.yaml` | 1 | `localhost:9094` |
| Standalone single-broker | `main/docker/kafka/single-broker/docker-compose.yaml` | 1 | `localhost:9094` |
| Multi-broker cluster | `main/docker/kafka/compose-config/broker{1,2,3}.yml` | 3 | `localhost:19091-19093` |

### Per-Service Configuration

| Setting | stream-service | chat-service | notification-service |
|---------|---------------|-------------|---------------------|
| **Bootstrap servers** | `${KAFKA_BOOTSTRAP_SERVERS:localhost:19091}` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:19091}` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:19091}` |
| **Consumer group** | `${KAFKA_GROUP_STREAM:stream-service}` | `chat-service` (hardcoded) | `${KAFKA_GROUP_NOTIFICATION:notification-service}` |
| **Auto-offset-reset** | `earliest` | `earliest` | `earliest` |
| **Ack mode** | — (producer only) | Default (batch) | `record` |
| **Producer acks** | `all` | — (consumer only) | — (consumer only) |
| **Admin auto-create** | `false` (C3) | — | — |
| **Auto-startup** | — | `${KAFKA_LISTENER_ENABLED:true}` | `${KAFKA_LISTENER_ENABLED:true}` |

### Environment Variables

| Variable | Default | File |
|----------|---------|------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19091,localhost:19092,localhost:19093` | `main/env/kafka.env` |
| `KAFKA_GROUP_NOTIFICATION` | `notification-service` | notification-service `application.yml` |
| `KAFKA_LISTENER_ENABLED` | `true` | chat-service, notification-service `application.yml` |
| `STREAM_CONTROL_TOPIC` | `stream.control` | chat-service, notification-service `application.yml` |
| `streaming.kafka.topic.stream-control` | `stream.control` | stream-service `application.yml` |
| `streaming.kafka.dlq-topic` | `stream.control.dlq` | chat-service, notification-service `KafkaConsumerConfig` |

---

## 11. Gaps & Debt

### Operational Gaps

| # | Gap | Severity | Status |
|---|-----|----------|--------|
| K1 | Outbox pattern implemented ✅ | — | Done (2026-07-14) |
| K2 | DLQ configured for consumers ✅ | — | Done (2026-07-14) |
| K3 | Consumer-side dedup (notification-service) ✅ | — | Done (2026-07-14) |
| K7 | Auto-create topics enabled | MEDIUM | ✅ Done (2026-08-01, C3) |
| K4 | No schema registry | HIGH | ⬜ Deferred (T3) |
| K5 | Consumer route-by-eventType | — | ✅ Done |
| K6 | No Kafka metrics (lag, error rate) | HIGH | ⬜ Deferred (T2) |
| K8 | Partition count unspecified | HIGH | ⬜ Deferred (T3) |
| K9 | Auto-commit offsets | MEDIUM | ⬜ Deferred (T3) |
| K11 | Per-topic retention not configured | LOW | ⬜ Deferred (T3) |
| K12 | No end-to-end integration test | MEDIUM | ⬜ Deferred (T2) |

### Code-Level Gaps

| # | Gap | Detail |
|---|-----|--------|
| G1 | **DLQ has no consumer** | Messages in `stream.control.dlq` accumulate silently. No alerting, replay, or dashboard. |
| G2 | **OutboxPoller failures not DLQ'd** | After 3 retries, outbox row is deleted. Comment says "pending Task A5." |
| G3 | **chat-service has local `StreamEvent` subset** | `com.streaming.chat.messaging.StreamEvent` with `@JsonIgnoreProperties`. If the canonical `StreamEvent` adds a required field, chat silently ignores it — or breaks if `ignoreUnknown` is removed. |
| G4 | **`StreamEventPublisher` still in codebase** | Fire-and-forget publisher is injected but unused. Dead code. |
| G5 | **chat-service consumer group is hardcoded** | `chat-service` string in `application.yml` — not overridable via env var, unlike notification-service. |
| G6 | **No idempotency at chat-service messaging layer** | Relies on DB-layer idempotency (unique index on `client_id`, `getOrCreate`). A duplicate `STREAM_CREATED` → duplicate system message if the room already exists. |

### Full Gap Analysis

For the complete prioritized gap list with remediation plans, see:
[REDIS-KAFKA-PRODUCTION-GAP.md](../REDIS-KAFKA-PRODUCTION-GAP.md)

---

## 12. Link Map

| Document | What It Covers |
|----------|---------------|
| [ADR-stream-0002: Kafka Event Publishing](../adr/stream/0002-kafka-event-publishing.md) | Reactive producer design, at-most-once trade-off, deferred concerns |
| [ADR-stream-0009: Outbox Pattern](../adr/stream/0009-outbox-pattern.md) | Transactional outbox for at-least-once Kafka delivery |
| [REDIS-KAFKA-PRODUCTION-GAP.md](../REDIS-KAFKA-PRODUCTION-GAP.md) | Prioritized gap backlog for Redis and Kafka |
| [C3 Retrospective: Kafka Topic Provisioning](../plans/C3-kafka-topic-provisioning-retrospective.md) | What was built, plan deviation (env var → shared library), architectural decisions, risks |
| `common/.../messaging/KafkaTopicConfig.java` | NewTopic bean definitions — single source of truth for topic config |
| [SERVICE-ARCHITECTURE.md](../SERVICE-ARCHITECTURE.md) | Kafka role: "decouple producers from consumers" |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | System-level architecture overview |

---

*Generated from the 2026-08-01 Kafka infrastructure survey. Update when topics, consumers, event types, or patterns change.*
