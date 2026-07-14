# Redis & Kafka — Production Readiness Gap Analysis

**Date:** 2026-07-13
**Status:** living — updated as gaps are closed
**Assessment scope:** `chat-service` (Redis), `stream-service` + `notification-service` (Kafka)

> **Purpose.** This document maps the delta between the current prototype-grade
> Redis/Kafka implementations and production-grade usage. It serves as:
>
> 1. A snapshot of what we handle today and what we don't
> 2. A prioritized backlog of gaps, each linked to the ADR where it was deferred
> 3. A launchpad for future ADRs — each gap becomes an ADR when we implement it
> 4. An interview-readiness map: what you can discuss now vs. what needs more depth
>
> This is NOT an ADR. ADRs record decisions already made. This records gaps not
> yet addressed, so the work is visible and prioritized.

---

## 1. Summary

| Dimension | Redis | Kafka |
|-----------|-------|-------|
| **Happy path** | ✅ Cache-aside with ZSETs, TTL, trimming | ✅ Reactive producer, KRaft 3-broker cluster, consumer scaffold |
| **Failure modes** | ✅ Evict-on-reconnect, evict-on-write-failure, timeouts | ❌ At-most-once (events can vanish), no DLQ |
| **Atomicity** | ❌ 3 round-trips per write (no Lua scripts) | ❌ No idempotent producer, no transactions |
| **Observability** | ❌ No Micrometer metrics, debug-level logs only | ❌ No lag monitoring, no producer/consumer metrics |
| **Schema evolution** | ⚠️ JSON strings (no versioning) | ❌ Raw JSON, no schema registry |
| **Scalability** | ⚠️ Single instance (no Sentinel/Cluster) | ⚠️ Partition count unspecified, no partitioning strategy doc |
| **Operational readiness** | ⚠️ Persistence configured, no backup/restore runbook | ⚠️ KRaft cluster exists, auto-create-topics still on |

**Overall: Redis is production-aware at small scale. Kafka is correct for a prototype.**
Both need work before either would survive a senior infrastructure interview.

---

## 2. Redis Gap Analysis

### 2.1 Current Implementation

| Aspect | What we have | Source |
|--------|-------------|--------|
| **Data structure** | ZSET per room (`chat:room:{roomKey}:recent`), scored by epoch-millis | `RedisMessageCache.java` |
| **Pattern** | Cache-aside: PG-first writes, Redis-first reads, async backfill | [ADR-chat-0001](adr/chat/0001-cache-aside-redis-zset.md) |
| **Retention** | 100 messages per room, `ZREMRANGEBYRANK` on write | `RedisMessageCache.trimToRetention()` |
| **Staleness defense** | TTL (1h, refreshed on write) + evict-on-reconnect + evict-on-write-failure | [ADR-chat-0003](adr/chat/0003-cache-staleness-on-redis-restart.md) |
| **Resilience** | 2s timeout on all ops, `.onErrorResume()` fallback to PG | `RedisMessageCache.java` |
| **Infrastructure** | Redis 7.2.4-alpine, AOF+RDB, 256MB maxmemory, allkeys-lru, password auth, health check, RedisInsight | `main/docker/redis/docker-compose.yml` |
| **Client** | `ReactiveStringRedisTemplate` (Lettuce) | Spring Boot auto-config |

### 2.2 Gap Table

| # | Gap | Severity | Tier | Linked ADR | What to do |
|---|-----|----------|------|------------|------------|
| R1 | **No Lua scripting** — write path is 3 round-trips (`ZADD` + `ZREMRANGEBYRANK` + `EXPIRE`). A network partition between step 1 and 2 leaves an untrimmed, no-TTL key. | HIGH | T2 | [ADR-chat-0003 § Implementation Plan step 4](adr/chat/0003-cache-staleness-on-redis-restart.md) | Port the write chain into a single Lua script: atomic, single round-trip. Already designed in [ADR-common-0002 § Refresh Token Lua Script Design](adr/common/0002-redis-ephemeral-data-store.md). |
| R2 | **No Micrometer metrics** — cache hit/miss/eviction are DEBUG logs only. Cannot answer "what's the cache hit rate per room?" without grep. | HIGH | T2 | [ADR-chat-0003 § Implementation Plan step 5](adr/chat/0003-cache-staleness-on-redis-restart.md) | Register `cache.hit`, `cache.miss`, `cache.eviction`, `cache.stale_eviction` counters via `MeterRegistry`. Expose to `/actuator/metrics`. Add a Grafana row. |
| R3 | **`allkeys-lru` eviction policy** — evicts by recency globally. A popular room's cache can be evicted because a bot hit 10,000 other keys. `allkeys-lfu` (frequency) or `volatile-lru` (only keys with TTL) may be more appropriate for a streaming workload. | MEDIUM | T2 | — | Benchmark both policies against a simulated hot-room workload. Document the choice. This is a one-line config change with significant behavioral difference. |
| R4 | **Implicit pipelining** — three `.flatMap()` calls send three sequential commands. Lettuce may batch at TCP level but offers no guarantee. Explicit pipelining (`redis.executePipelined()`) guarantees one TCP frame. | MEDIUM | T3 | — | Replace `.flatMap(add).flatMap(trim).flatMap(ttl)` with `redis.executePipelined(conn -> { ... })`. Understand the difference between pipelining (client-side batching, no atomicity) and Lua scripting (server-side atomic execution). |
| R5 | **JSON serialization overhead** — every message serialized/deserialized via Jackson. At 100 messages/room this is negligible. At 10,000 messages/room it's measurable. | LOW | T3 | [ADR-chat-0001 § Alternative 2](adr/chat/0001-cache-aside-redis-zset.md) | Know the alternatives: Redis Streams (already deferred), MessagePack/Protobuf for smaller payloads, or client-side caching (Redis 6+ `CLIENT TRACKING`). Don't build — just know the options. |
| R6 | **Single Redis instance** — no Sentinel, no Cluster. One process = one point of failure. | MEDIUM | T3 | [ADR-common-0002 § Risks](adr/common/0002-redis-ephemeral-data-store.md) | Understand Redis Sentinel (automatic failover with 3+ nodes) and Redis Cluster (horizontal sharding via hash slots). The Spring Data Redis config change is in the factory bean, not the cache code. |
| R7 | **No connection pooling awareness** — `ReactiveRedisConnectionFactory` shares one connection across all reactive subscriptions. At high concurrency, this becomes a bottleneck. | LOW | T3 | — | Know that `LettucePoolingClientConfiguration` exists. The reactive paradigm makes pooling less critical than blocking code, but at 1,000+ concurrent subscriptions, the single connection's TCP window fills. |
| R8 | **Ephemeral data store use cases not yet implemented** — refresh tokens, rate limiting, idempotency keys, WebSocket session affinity, distributed locking are all designed but not built. | MEDIUM | T2 | [ADR-common-0002](adr/common/0002-redis-ephemeral-data-store.md) | Each use case is its own mini-project. Priority order is in ADR-common-0002 § Priority. Refresh tokens are the highest-impact item — moves auth hot-path from PG to Redis. |
| R9 | **No backup/restore runbook** — AOF+RDB is configured, but there's no documented procedure for restoring from a corrupted RDB file or replaying AOF. | LOW | T3 | — | Document the commands: `redis-check-aof --fix`, `redis-check-rdb`, S3 backup of `/data/dump.rdb`. Operational, not code. |

### 2.3 Redis Interview Readiness

**You can discuss now (T1):**
- Cache-aside pattern with defense-in-depth against staleness (3 scenarios from ADR-0003)
- Why ZSET over LIST or Streams for the hot-cache use case
- Why PG-first writes (durability beats write latency for chat)
- TTL-based staleness bounding + evict-on-reconnect + evict-on-write-failure

**Build before the interview (T2):**
- Write a Lua script that atomically adds + trims + sets TTL
- Wire Micrometer counters for cache hit/miss/eviction
- Benchmark `allkeys-lru` vs `allkeys-lfu` for a hot-room workload

**Design-level understanding (T3):**
- Redis Sentinel vs Cluster — when to use each
- Pipelining vs Lua scripting — atomicity vs. batching
- Client-side caching (`CLIENT TRACKING`) — how it works
- Redis Streams as a Kafka-lite alternative

---

## 3. Kafka Gap Analysis

### 3.1 Current Implementation

| Aspect | What we have | Source |
|--------|-------------|--------|
| **Cluster** | KRaft mode, 3-broker (prod config) + single-broker (dev config), Kafka 4.1.2 | `main/docker/kafka/` |
| **Producer** | `KafkaTemplate<String, String>`, Jackson JSON, reactive bridge via `Mono.fromFuture()` + `Schedulers.boundedElastic()`, fire-and-forget, at-most-once | `StreamEventPublisher.java` |
| **Consumer** | `@KafkaListener` on `notification-service`, logs raw payload, auto-commit enabled | `StreamControlListener.java` |
| **Event envelope** | `StreamEvent` record: `eventType`, `streamId`, `eventId`, `timestamp`, `broadcasterSubject`. Stream keys excluded. | [ADR-stream-0002](adr/stream/0002-kafka-event-publishing.md) |
| **Topic config** | `stream.control` topic, auto-created, no explicit partition count, replication factor 3 (multi-broker) / 1 (single-broker) | `application.yml` + broker config |
| **Tooling** | Kafka-UI for visual inspection | `main/docker/kafka/docker-compose.yml` |

### 3.2 Gap Table

| # | Gap | Severity | Tier | Linked ADR | What to do |
|---|-----|----------|------|------------|------------|
| K1 | ~~**At-most-once delivery**~~ ✅ **DONE (2026-07-14).** Outbox pattern implemented: `V13__create_outbox.sql` migration, `OutboxEvent` entity, `OutboxEventRepository` with `FOR UPDATE SKIP LOCKED`, `OutboxWriter` (transactional), `OutboxPoller` (@Scheduled poller → Kafka → delete/retry). See [ADR-stream-0009](adr/stream/0009-outbox-pattern.md). | ~~CRITICAL~~ ✅ | T2 | [ADR-stream-0009](adr/stream/0009-outbox-pattern.md) | Done. Known limitation: `@Transactional` not yet wired — entity save and outbox write are in separate auto-commit transactions. Consumer idempotency via Redis `SETNX` mitigates duplicate risk. |
| K2 | ~~**No Dead Letter Queue**~~ ✅ **DONE (2026-07-14).** `KafkaConsumerConfig` in notification-service with `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. 3 retries (1s fixed backoff) → `stream.control.dlq`. Deserialization errors not retried. | ~~HIGH~~ ✅ | T2 | — | Done. DLQ monitoring (alert on messages in DLQ) deferred to observability phase. |
| K3 | ~~**No idempotent producer**~~ ✅ **DONE (2026-07-14).** Consumer-side dedup via Redis `SETNX` on `eventId` with 24h TTL in `StreamControlListener`. `eventId` already exists on the event envelope. Note: producer-side `enable.idempotence=true` was NOT added (outbox poller uses sync `KafkaTemplate` — idempotent producer is a Spring Kafka config, not yet tested with the outbox poller pattern). | HIGH → MEDIUM (consumer-side done) | T2 | [ADR-stream-0009](adr/stream/0009-outbox-pattern.md) | Consumer-side dedup done. Producer-side `enable.idempotence=true` + `acks=all` deferred — evaluate interaction with outbox poller's sync `KafkaTemplate` in follow-up. |
| K4 | **No schema registry** — events are raw JSON strings. Consumers parse manually. No schema evolution story. | HIGH | T3 | [ADR-stream-0002 § Deferred Concerns §4](adr/stream/0002-kafka-event-publishing.md) | Evaluate Avro + Apicurio Registry (open-source, no Confluent license). Define `.avsc` schemas for `StreamCreated`, `StreamStarted`, `StreamEnded`. Understand compatibility modes: BACKWARD (can read old data with new schema), FORWARD (old consumers can read new data), FULL (both). |
| K5 | **Consumer only logs** — `StreamControlListener.onStreamControl(String payload)` does nothing with the event. It's a placeholder. | MEDIUM | T2 | — | Deserialize the JSON into `StreamEvent` in the listener. Route by `eventType` to handlers: `STREAM_CREATED` → create notification preferences, `STREAM_STARTED` → enqueue "streamer went live" notification, `STREAM_ENDED` → enqueue VOD-ready notification. |
| K6 | **No producer/consumer metrics** — zero Micrometer exposure. Cannot answer: "What's the consumer lag?" or "What's the producer error rate?" | HIGH | T2 | — | Expose Kafka client metrics via Micrometer (Spring Kafka auto-config already provides them — just expose via `/actuator/metrics`). Key metrics: `kafka.consumer.fetch.manager.records.lag`, `kafka.producer.record.send.rate`, `kafka.producer.record.error.rate`. Lag > threshold = page. |
| K7 | **`AUTO_CREATE_TOPICS_ENABLE: true`** — an accidental `send()` to a misspelled topic creates it silently with default settings. Production anti-pattern. | MEDIUM | T2 | — | Disable auto-create. Define topics explicitly in Compose (Kafka 4.x supports topic provisioning via `kafka-topics.sh` in an init container or via `KAFKA_CREATE_TOPICS` env var). Document partition count, replication factor, and retention policy per topic. |
| K8 | **Partition count unspecified** — `stream.control` is auto-created with default partitions (likely 1). Single partition = single consumer = no parallelism. | HIGH | T3 | — | Choose partition count for each topic: `stream.control` (ordered per stream → partition by `streamId`, count ≥ max concurrent streams), future `chat.messages` (ordered per room → partition by `roomId`), future `notifications.push` (no ordering needed → round-robin). Understand co-partitioning for stream-table joins. |
| K9 | **Auto-commit consumer offsets** — `enable.auto.commit: true` (Spring default). Consumer crashes after processing but before next auto-commit interval → message re-processed on restart. | MEDIUM | T3 | — | Understand the trade-off: auto-commit (simpler, at-most-once-ish) vs. manual commit (exactly-once-ish, more code). For notification dispatch, manual commit after successful send is correct. |
| K10 | ~~**Fire-and-forget lifecycle issue**~~ ✅ **DONE (2026-07-14).** All `eventPublisher.publish(event).subscribe()` in `doOnSuccess` replaced with `outboxWriter.write(event)` chained via `flatMap`. The outbox write is now part of the reactive chain, not a side-effect callback. This eliminates the hot-subscription risk — the write participates in the connection lifecycle. | ~~MEDIUM~~ ✅ | T2 | [ADR-stream-0009](adr/stream/0009-outbox-pattern.md) | Done as part of outbox pattern implementation. See `StreamService.java` — all 6 lifecycle event sites refactored. |
| K11 | **Log retention hours only** — `KAFKA_LOG_RETENTION_HOURS: 168` (7 days). For `stream.control` (lifecycle events), this is reasonable. For future `chat.messages` (if we move chat to Kafka), this would mean chat history disappears after 7 days. | LOW | T3 | — | Per-topic retention policies. `stream.control`: 7 days (lifecycle events are small). Future `chat.messages`: consider **compacted topics** (Kafka keeps the latest message per key indefinitely) or **tiered storage** (older segments to S3). |
| K12 | **No end-to-end integration test** — the producer and consumer are wired, but there's no test that verifies: "publish a `StreamEvent` → it appears in the `stream.control` topic → consumer reads and processes it." | MEDIUM | T2 | — | Write a Testcontainers-based integration test: `KafkaContainer` + producer bean + consumer bean → send event → await consumption → assert handler was called. The notification-service already has a Testcontainers pattern from chat-service (see `AbstractRedisIntegrationTest`). |

### 3.3 Kafka Interview Readiness

**You can discuss now (T1):**
- Why KRaft over ZooKeeper (ZooKeeper removed in Kafka 4.0, KRaft uses Raft consensus embedded in brokers)
- Why `Mono.fromFuture()` + `Schedulers.boundedElastic()` for reactive Kafka (doesn't block Netty event loop)
- At-most-once trade-off (you chose it deliberately, documented it, know the path to outbox)
- 3-broker cluster vs. single-broker dev setup (KRaft quorum: 3 controllers for fault tolerance)
- Why stream keys are excluded from event payloads (security — events may be consumed by services that shouldn't see publish secrets)

**Build before the interview (T2):**
- Outbox Pattern (transactional write + poller + retry + idempotent consumer)
- Dead Letter Queue for the consumer
- Idempotent producer (`enable.idempotence=true` + `acks=all`)
- Micrometer metrics for producer error rate and consumer lag

**Design-level understanding (T3):**
- Schema registry + Avro: schema evolution rules (BACKWARD/FORWARD/FULL compatibility), subject naming strategy
- Partitioning: how `hash(key) % partitions` works, why adding partitions is expensive, co-partitioning for Kafka Streams joins
- Consumer group rebalancing: eager vs. cooperative, static group membership, the stop-the-world problem
- Compaction vs. retention: when to use each, how compacted topics enable changelog-style state

---

## 4. Shared Gap: Observability

Both Redis and Kafka share the same observability gap: **no metrics, no dashboards, no alerting.** This is a production readiness issue independent of any specific technology.

| What | Current | Target |
|------|---------|--------|
| **Structured logging** | ✅ Implemented (ADR-common-0001) | ✅ Done |
| **Trace propagation** | ⚠️ Designed in `TRACE-PROPAGATION.md`, not wired | `X-Trace-Id` through gateway → service → Kafka header → consumer |
| **Redis metrics** | ❌ | `cache.hit`, `cache.miss`, `cache.eviction`, P99 latency, connection pool utilization |
| **Kafka metrics** | ❌ | Consumer lag, producer send/error rate, consumer fetch rate, rebalance count |
| **Health checks** | ⚠️ Partial — Redis has compose health check, Kafka does not | Actuator health endpoints for all services, Redis/Kafka connectivity included |
| **Dashboards** | ❌ | Grafana dashboard rows for Redis cache health and Kafka consumer lag |
| **Alerting** | ❌ | Consumer lag > threshold → page; cache hit rate < 80% → warn |

This gap spans ADRs:
- [ADR-common-0001](adr/common/0001-structured-json-logging.md) — logging (done)
- [ADR-chat-0003 § Implementation Plan step 5](adr/chat/0003-cache-staleness-on-redis-restart.md) — Redis metrics (deferred)
- [ADR-stream-0002 § Deferred Concerns](adr/stream/0002-kafka-event-publishing.md) — Kafka DLQ/retry (deferred)
- [TRACE-PROPAGATION.md](TRACE-PROPAGATION.md) — tracing design (not wired)

---

## 5. Priority Roadmap

Ordered by interview impact × implementation effort.

### Immediate (T2 — build before interviewing)

| Order | Gap | Effort | Why first |
|-------|-----|--------|-----------|
| 1 | **K1 — Outbox Pattern** | 1–2 days | Transforms the Kafka conversation from "I used KafkaTemplate" to "I designed an at-least-once event pipeline." Most common Kafka interview question. |
| 2 | **K2/K3 — DLQ + Idempotent Producer** | ~3 hours | Two config changes + one error handler. Together with outbox, closes the Kafka reliability story. |
| 3 | **R1 — Redis Lua script** | ~2 hours | Atomic write path. Closes the "three round-trips" weakness in the current design. |
| 4 | **R2/K6 — Metrics** | ~4 hours | Micrometer counters for Redis cache and Kafka consumer lag. Enables the "how do you monitor this?" conversation. |

### Short-term (T2 — build soon after)

| Order | Gap | Effort | Why |
|-------|-----|--------|-----|
| 5 | **K10 — Fire-and-forget lifecycle fix** | ~2 hours | Real bug class in reactive systems. Shows understanding of Reactor context lifecycle. |
| 6 | **K5 — Consumer route-by-eventType** | ~3 hours | Makes the consumer do real work. Closes the "consumer only logs" gap. |
| 7 | **K7 — Disable auto-create topics** | ~30 min | One-line config change. Shows operational awareness. |
| 8 | **R8 — Refresh tokens to Redis** | 1–2 days | Highest-impact Redis use case per ADR-common-0002. Lua script practice. |

### Medium-term (T3 — understand deeply, build selectively)

| Order | Gap | Effort | Why |
|-------|-----|--------|-----|
| 9 | **K4 — Schema Registry** | 2–3 days | Avro + Apicurio. The "how do you evolve event schemas?" conversation. |
| 10 | **K8 — Partitioning strategy** | Design doc | More design than code. Show understanding of partition mechanics. |
| 11 | **R6 — Redis Sentinel** | 1 day (config) | Sentinel-aware `RedisConnectionFactory`. Operational, not application logic. |
| 12 | **R3 — Eviction policy benchmark** | ~3 hours | Benchmark + document the choice. Evidence-based config decisions. |

### Long-term (T3 — knowledge only for now)

| Order | Gap | Why |
|-------|-----|-----|
| 13 | **K9 — Manual offset commit** | Understand the trade-off. Build when notification dispatch has side effects that must not duplicate. |
| 14 | **K11 — Per-topic retention** | Relevant when we have more than one topic. Compacted topics for changelog data. |
| 15 | **R4 — Explicit pipelining** | Nice optimization. Less impactful than Lua scripting for the same problem. |
| 16 | **R5 — Serialization alternatives** | MessagePack/Protobuf. Relevant when JSON overhead becomes measurable. |
| 17 | **R7 — Connection pooling** | Relevant at 1,000+ concurrent subscriptions. |

---

## 6. Link Map

### Existing docs referenced by this analysis

| Doc | What it covers |
|-----|---------------|
| [ADR-chat-0001](adr/chat/0001-cache-aside-redis-zset.md) | Cache-aside pattern with ZSET — the foundation |
| [ADR-chat-0003](adr/chat/0003-cache-staleness-on-redis-restart.md) | Three staleness scenarios, TTL + eviction defense-in-depth |
| [ADR-common-0002](adr/common/0002-redis-ephemeral-data-store.md) | All Redis use cases beyond chat cache, Lua script design |
| [ADR-stream-0002](adr/stream/0002-kafka-event-publishing.md) | Reactive Kafka producer, at-most-once trade-off, deferred concerns |
| [TRACE-PROPAGATION.md](TRACE-PROPAGATION.md) | Trace propagation design (not yet implemented) |
| [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md#phase-6--production-hardening) | Phase 6 hardening checklist |
| [SERVICE-ARCHITECTURE.md](SERVICE-ARCHITECTURE.md) | Redis role: "latency buffer"; Kafka role: "decouple producers from consumers" |

### Future ADRs this document launches

When you implement a gap, write the ADR:

| Gap | Future ADR | When |
|-----|-----------|------|
| K1 | `ADR-stream-0009: Outbox Pattern for At-Least-Once Kafka Delivery` | Outbox implementation |
| K4 | `ADR-common-0003: Avro Schema Registry for Event Evolution` | Schema registry integration |
| R1 | `ADR-chat-0009: Lua Script for Atomic Cache Write Path` | Lua script implementation |
| R8 | `ADR-auth-0004: Refresh Token Storage in Redis with Lua Atomic Rotation` | Refresh token migration |
| K8 | `ADR-common-0004: Kafka Topic Partitioning and Naming Convention` | Partitioning strategy |
| Observability | `ADR-common-0005: Infrastructure Metrics and Alerting Standards` | Metrics implementation |

---

## 7. How to Use This Document

### Before an interview

1. Re-read the "You can discuss now (T1)" sections for Redis and Kafka
2. If you've built any T2 items, re-read those ADRs
3. Practice whiteboarding: "Here's the architecture, here's what we handle, here's what we'd improve"

### During a work session

1. Pick the highest-priority gap you have time for
2. Read the linked ADR for context
3. Implement
4. Write the corresponding ADR (use the names in §6 Future ADRs)
5. Update this document: mark the gap as `✅ Done`, add commit hash, move to a new `## 8. Closed Gaps` section

### For code review

- If a PR touches `RedisMessageCache`, `StreamEventPublisher`, or `StreamControlListener`, check this document — does the PR close a gap? Does it introduce a new one?
- New gaps should be added to the appropriate section, not left as PR comments that scroll away.

---

*Generated from the 2026-07-13 Redis/Kafka production readiness review.
Update after each gap closure.*
