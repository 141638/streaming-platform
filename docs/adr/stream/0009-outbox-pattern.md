# ADR-0009: Outbox Pattern for At-Least-Once Kafka Event Delivery

**Status:** Accepted
**Date:** 2026-07-14
**Domain:** Stream Service

## Context

The stream service publishes lifecycle events (`STREAM_CREATED`, `STREAM_STARTED`, `STREAM_ENDED`, etc.) to Kafka for downstream consumers (notification-service, chat-service). The original implementation ([ADR-0002](0002-kafka-event-publishing.md)) used fire-and-forget publishing with `.onErrorComplete()` — if Kafka was unreachable for 2 seconds, the event was silently dropped while the HTTP response and database write succeeded.

This at-most-once delivery was intentional for Phase 2.0 as a trade-off: "Events can be lost if Kafka is down (mitigated by structured logging; proper fix deferred)." With Phase 4 (Viewer Experience) and Phase 5 (Notifications) approaching, durable event delivery became a prerequisite — downstream features cannot be built on an unreliable event backbone.

The outbox pattern was the #1 deferred concern from ADR-0002 and the highest-priority gap identified in the [Redis/Kafka Production Gap Analysis](../../REDIS-KAFKA-PRODUCTION-GAP.md) (gap K1).

## Decision

We implemented the **Transactional Outbox Pattern** using a scheduled poller — not CDC (Debezium) — for Phase 2.5/4.0.

### Architecture

```
POST /streams/{id}/start
  │
  ├─ BEGIN TRANSACTION ──────────────────────────┐
  │  UPDATE stream SET status = 'LIVE'             │
  │  INSERT INTO outbox (event_type, stream_id,    │  ← same DB transaction
  │      payload) VALUES (...)                     │
  │  COMMIT                                        │
  └────────────────────────────────────────────────┘
  HTTP 200

... OutboxPoller (@Scheduled, fixedDelay=5s) ...

  SELECT * FROM stream.outbox
  WHERE published = false
  ORDER BY created_at, id
  FOR UPDATE SKIP LOCKED      ← concurrent safety
  LIMIT 50

  For each row:
    kafkaTemplate.send(topic, key, payload)
    → success: DELETE FROM outbox WHERE id = ?
    → failure: increment retry_count, retry next cycle
    → after 3 retries: delete row + log ERROR (DLQ pending)
```

### Key Components

| Component | Package | Purpose |
|-----------|---------|---------|
| `V13__create_outbox.sql` | `db/migration/` | Outbox table with partial index on unpublished rows |
| `OutboxEvent` | `persistence/entity/` | R2DBC entity mapping to `stream.outbox` (`Persistable<UUID>`) |
| `OutboxEventRepository` | `persistence/repository/` | `ReactiveCrudRepository` + `findUnpublished(limit)` with `FOR UPDATE SKIP LOCKED` |
| `OutboxWriter` | `service/` | Serializes `StreamEvent` → inserts `OutboxEvent` within caller's transaction |
| `OutboxPoller` | `messaging/` | `@Scheduled` poller: read batch → publish → delete/retry |
| `StreamControlListener` | `messaging/` (notification-service) | Consumer: JSON deserialization → Redis `SETNX` dedup → route by type |
| `KafkaConsumerConfig` | `config/` (notification-service) | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (3 retries → DLQ) |

### StreamService Refactoring

All `eventPublisher.publish(event).subscribe()` fire-and-forget calls were replaced with `outboxWriter.write(event)` chained via `flatMap` in the reactive pipeline. The outbox write now participates in the same R2DBC connection as the entity save.

### StreamEvent Relocation

`StreamEvent` was moved from `com.streaming.stream.messaging` to `com.streaming.common.messaging` so both the stream-service producer and notification-service consumer share the canonical event record without duplication.

### Consumer Idempotency

The notification-service consumer uses Redis `SETNX` on `eventId` with 24h TTL. This is defense-in-depth — the outbox guarantees at-least-once delivery, but Kafka can deliver duplicates during partition rebalancing or producer retries.

**Important:** Dedup keys must be consumer-group-scoped per [ADR common/0003](../common/0003-cross-service-event-dedup-key-scoping.md). The prescribed format is `dedup:{topic}:{consumerGroupId}:{eventId}`. The current notification-service `StreamControlListener` uses an unscoped key (`dedup:stream-event:{eventId}`) — safe today because only one service dedups `stream.control`, but must be refactored before other services add Redis SETNX for the same topic.

### Dead Letter Queue

The notification-service consumer is configured with a `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. After 3 retries (1-second fixed backoff), poison-pill messages are forwarded to `stream.control.dlq`. Deserialization errors are not retried (they can never succeed).

### Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Poller mechanism | `@Scheduled(fixedDelay)` | Simpler than CDC (Debezium) for single-service POC; sufficient for stream lifecycle events (~1 event per stream lifecycle transition) |
| Batch size | 50 rows per poll | Balances Kafka batch throughput vs. row-lock duration |
| Retry strategy | 3 retries, fixed 1s backoff | Standard pattern; DLQ for manual inspection after exhaustion |
| Row cleanup | `DELETE` on successful publish | Keeps outbox table small; DLQ messages persist in the dead-letter topic |
| Poll interval | 5 seconds | Acceptable latency for lifecycle events (real-time chat uses separate Redis/REST path, not Kafka) |
| Concurrent safety | `FOR UPDATE SKIP LOCKED` | Safe for horizontal scaling — multiple poller instances won't double-publish |
| Consumer dedup TTL | 24 hours via Redis `SETNX` | Event replay window is bounded; 24h covers all realistic retry scenarios |

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Debezium CDC (change data capture) | Deferred to Phase 6+ | Adds operational complexity (separate process, Kafka Connect cluster); overkill for current event volume |
| Outbox table + soft-delete (published flag) | Rejected | Accumulates rows indefinitely; DELETE keeps the table small and query efficient |
| Reactive Kafka producer (spring-kafka reactive) | Deferred | Evaluated in ADR-0002; outbox pattern makes it unnecessary — poller uses sync `KafkaTemplate` from a scheduler thread |
| Kafka transactions (exactly-once) | Deferred to Phase 6+ | Requires idempotent producer + transactional consumers; outbox provides sufficient at-least-once for current needs |
| No outbox — just remove `.onErrorComplete()` | Rejected | Still doesn't guarantee delivery — if Kafka is down, the HTTP response would error, but the entity save already committed (dual-write without atomicity) |

### Deferred Concerns (Future ADRs)

1. **DLQ replay tooling**: Admin endpoint or CLI to re-publish dead-lettered events after manual inspection
2. **Outbox table partitioning**: If the outbox table grows (e.g., from high-frequency events in Phase 5+), partition by `created_at` for efficient range scans and cleanup
3. **Metrics**: Micrometer gauges for outbox depth, publish latency, retry rate, and DLQ size
4. **Dead-letter cleanup**: Scheduled cleanup of DLQ messages older than N days
5. **Schema Registry**: Avro or JSON Schema for event evolution as downstream consumers multiply (also deferred from ADR-0002)

## Consequences

- **Positive**: Events are guaranteed at-least-once delivery; consumer idempotency handles duplicates
- **Positive**: Outbox writer participates in the same R2DBC connection as entity persistence (transaction-safe)
- **Positive**: `FOR UPDATE SKIP LOCKED` enables horizontal scaling of the poller
- **Positive**: Consumer-side dedup via Redis `SETNX` provides defense-in-depth against duplicates
- **Positive**: DLQ captures poison-pill messages for manual inspection instead of silent loss
- **Negative**: ~5-second latency from entity change to Kafka delivery (acceptable for lifecycle events, not suitable for real-time chat — which uses Redis/REST)
- **Negative**: Outbox table is an additional write per lifecycle transition (mitigated by DELETE-on-success keeping the table small)
- **Negative**: Requires `@Transactional` on service methods for correct atomicity (not yet wired — entity save and outbox write are in separate auto-commit transactions in the initial implementation; this is a known limitation to be addressed in a follow-up)

## References

- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md) — original at-most-once design, deferred outbox
- [ADRs 0001–0008](../stream/) — stream service foundation
- [Outbox + Phase 4 Blueprint](../../plans/outbox-and-phase4-blueprint.md) — implementation plan
- [Redis/Kafka Production Gap Analysis](../../REDIS-KAFKA-PRODUCTION-GAP.md) — gap K1 (outbox)
- `V13__create_outbox.sql` — outbox table migration
- `OutboxEvent.java`, `OutboxEventRepository.java` — persistence layer
- `OutboxWriter.java`, `OutboxPoller.java` — application layer
- `StreamControlListener.java` (notification-service) — consumer with dedup
- `KafkaConsumerConfig.java` (notification-service) — DLQ error handler
