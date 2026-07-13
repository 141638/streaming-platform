# Outbox Pattern + Phase 4 Viewer Experience — Implementation Blueprint

**Date:** 2026-07-13
**Status:** Ready — not yet started
**Parent:** [REDIS-KAFKA-PRODUCTION-GAP.md](../REDIS-KAFKA-PRODUCTION-GAP.md) (gap K1), [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) (Phase 4)

## Summary

Two-phase implementation: first close the #1 Kafka production gap (Outbox Pattern for at-least-once event delivery), then build Phase 4.0–4.2 so a viewer can discover and watch a stream with embedded chat. The outbox ships first because every downstream feature (notifications, viewer presence, Phase 5) benefits from reliable event delivery.

## Decision: Path C (Hybrid)

Chosen over:
- **Path A (product-first):** Builds the demo faster, but on an unreliable event backbone — `STREAM_STARTED` events can be silently dropped
- **Path B (infrastructure-only):** Deepens Kafka/Redis knowledge but doesn't move the product forward

Path C closes the single highest-leverage gap (outbox = the most common Kafka interview question) then immediately applies it in the viewer experience.

---

## Phase A: Outbox Pattern (K1) — ~1-2 days

### Why this first

The current `StreamEventPublisher` uses at-most-once delivery: if Kafka is unreachable for 2 seconds, `.onErrorComplete()` silently drops the event. The HTTP response succeeds, the PG write succeeded, but downstream consumers never learn the stream started.

The outbox pattern guarantees at-least-once delivery by writing events to an `outbox` table in the **same database transaction** as the entity change. A scheduled poller reads unpublished events and publishes them to Kafka. On success, the outbox row is deleted. On failure, it retries with backoff.

This transforms the Kafka conversation from "I called `KafkaTemplate.send()`" to "I designed an at-least-once event pipeline with transactional guarantees and idempotent consumers."

### Reference

- [ADR-stream-0002: Kafka Event Publishing § Deferred Concerns](../adr/stream/0002-kafka-event-publishing.md) — at-most-once trade-off, outbox deferred
- [REDIS-KAFKA-PRODUCTION-GAP.md § K1](../REDIS-KAFKA-PRODUCTION-GAP.md) — gap analysis entry
- Future ADR: `docs/adr/stream/0009-outbox-pattern.md` — write during implementation

### Architecture

```
┌─ PostgreSQL Transaction (stream-service) ───────────┐
│                                                       │
│  1. UPDATE stream SET status = 'LIVE' WHERE id = ?    │
│  2. INSERT INTO outbox (event_type, stream_id,        │
│        payload, created_at) VALUES (...)               │  ← same TX
│                                                       │
└───────────────────────────────────────────────────────┘
                          │
                          ▼
┌─ OutboxPoller (@Scheduled, fixedDelay) ────────────┐
│                                                       │
│  SELECT * FROM outbox                                 │
│  WHERE published = false                              │
│  ORDER BY id                                          │
│  FOR UPDATE SKIP LOCKED                               │  ← concurrent safety
│  LIMIT 50                                             │
│                                                       │
│  for each row:                                        │
│    kafkaTemplate.send(topic, key, payload)             │
│    → success: DELETE FROM outbox WHERE id = ?         │
│    → failure: increment retry_count, exponential      │
│      backoff, dead-letter after N retries             │
│                                                       │
└───────────────────────────────────────────────────────┘
                          │
                          ▼
┌─ Consumer (notification-service) ──────────────────┐
│                                                       │
│  @KafkaListener                                       │
│  → deserialize StreamEvent                            │
│  → check eventId against dedup cache (Redis SETNX)   │  ← idempotency
│  → route by eventType                                 │
│  → STREAM_STARTED: enqueue notification               │
│  → STREAM_ENDED: enqueue VOD notification             │
│                                                       │
└───────────────────────────────────────────────────────┘
```

### Design Decisions (to be confirmed during implementation)

| Decision | Recommendation | Rationale |
|----------|---------------|-----------|
| **Outbox poller scheduling** | `@Scheduled(fixedDelay)` in Spring, not a separate process | Single-service POC; a separate CDC connector (Debezium) is Phase 6+ |
| **Poll batch size** | 50 rows per poll | Small enough to keep Kafka batches tight, large enough to clear backlogs quickly |
| **Retry strategy** | 3 retries with exponential backoff (1s, 5s, 25s), then dead-letter | Standard pattern; dead-letter topic `stream.control.dlq` |
| **Idempotency at consumer** | Redis `SETNX` on `eventId` with 24h TTL | `eventId` already exists on the event envelope; consumer-side dedup is defense-in-depth |
| **Outbox table cleanup** | DELETE on successful publish (not soft-delete) | Keeps the table small; DLQ messages persist in the dead-letter topic |
| **Polling interval** | 5 seconds | Balances latency (max 5s delay for event delivery) vs. PG load |
| **Concurrent safety** | `FOR UPDATE SKIP LOCKED` | Allows multiple poller instances (when service scales) without double-publishing |

### Files to Create/Modify

| File | Action | What |
|------|--------|------|
| **Migration** | | |
| `stream-service/.../db/migration/V13__create_outbox.sql` | CREATE | `outbox` table: `id UUID PK`, `event_type VARCHAR`, `stream_id VARCHAR`, `payload JSONB`, `retry_count INT DEFAULT 0`, `created_at TIMESTAMPTZ`, `last_attempt_at TIMESTAMPTZ`, `published BOOLEAN DEFAULT false` |
| **Domain** | | |
| `stream-service/.../domain/OutboxEvent.java` | CREATE | Entity mapping to `outbox` table, `Persistable<UUID>` pattern |
| `stream-service/.../domain/OutboxEventRepository.java` | CREATE | `ReactiveCrudRepository` + custom query: `findUnpublished(limit)` with `FOR UPDATE SKIP LOCKED` via `@Query` |
| **Application** | | |
| `stream-service/.../application/OutboxPoller.java` | CREATE | `@Scheduled` poller: read batch → publish to Kafka → delete on success → increment retry on failure |
| `stream-service/.../application/OutboxWriter.java` | CREATE | Service that writes to outbox — called from `StreamService` within the same transaction |
| **Service (refactor)** | | |
| `stream-service/.../service/StreamService.java` | MODIFY | Replace `eventPublisher.publish(event).subscribe()` with `outboxWriter.write(event)` in the same transactional method |
| **Messaging (refactor)** | | |
| `stream-service/.../messaging/StreamEventPublisher.java` | MODIFY | Keep the reactive wrapper but remove `.onErrorComplete()` — the outbox poller calls `send()` synchronously and handles failures |
| **Consumer** | | |
| `notification-service/.../messaging/StreamControlListener.java` | MODIFY | Deserialize JSON → `StreamEvent`, route by `eventType`, dedup via Redis `SETNX` on `eventId` |
| **Config** | | |
| `stream-service/.../application.yml` | MODIFY | Add `streaming.outbox.poll-interval-ms: 5000`, `streaming.outbox.batch-size: 50`, `streaming.outbox.max-retries: 3` |
| `notification-service/.../application.yml` | MODIFY | Add Redis dependency for dedup cache (if not already present) |
| **Tests** | | |
| `stream-service/.../application/OutboxPollerTest.java` | CREATE | Testcontainers: `KafkaContainer` + real PostgreSQL → write to outbox → verify poller publishes → verify row deleted |
| `stream-service/.../application/OutboxWriterTest.java` | CREATE | Unit test: verify outbox write happens in same transaction |
| `notification-service/.../messaging/StreamControlListenerTest.java` | CREATE | Unit test: event routing + dedup logic |
| **Docs** | | |
| `docs/adr/stream/0009-outbox-pattern.md` | CREATE | ADR covering the decision, alternatives (CDC, no outbox), trade-offs, deferred concerns (DLQ, schema registry) |

### Tasks

#### Task A1: Outbox Schema
- **Action**: Create `V13__create_outbox.sql` migration + `OutboxEvent` entity + `OutboxEventRepository`
- **Files**: `V13__create_outbox.sql`, `OutboxEvent.java`, `OutboxEventRepository.java`
- **Validate**: `./gradlew :stream-service:compileJava` — entity maps to table

#### Task A2: Outbox Writer
- **Action**: Create `OutboxWriter` — called from `StreamService` transactional methods, writes `OutboxEvent` in same transaction
- **Files**: `OutboxWriter.java`, `StreamService.java` (modify — replace `.subscribe()` with `outboxWriter.write()`)
- **Validate**: Unit test verifies outbox row exists after `StreamService.handlePublish()`

#### Task A3: Outbox Poller
- **Action**: Create `OutboxPoller` — scheduled poll with `FOR UPDATE SKIP LOCKED`, Kafka send, delete on success, retry on failure
- **Files**: `OutboxPoller.java`, `application.yml` (config)
- **Validate**: Integration test with Testcontainers (KafkaContainer + PostgreSQL)

#### Task A4: Consumer Idempotency
- **Action**: Refactor `StreamControlListener` — deserialize JSON → `StreamEvent`, dedup via Redis `SETNX`, route by `eventType`
- **Files**: `StreamControlListener.java`, `application.yml` (Redis config)
- **Validate**: Unit test: duplicate `eventId` → second call is no-op

#### Task A5: Dead Letter Queue
- **Action**: Add `SeekToCurrentErrorHandler` + `DeadLetterPublishingRecoverer` to consumer config. After 3 retries, publish to `stream.control.dlq`
- **Files**: `notification-service/.../config/KafkaConsumerConfig.java` (create or modify)
- **Validate**: Integration test: poison pill message → appears in DLQ topic

#### Task A6: Documentation
- **Action**: Write `ADR-stream-0009` — decision, alternatives, trade-offs, deferred concerns. Update `REDIS-KAFKA-PRODUCTION-GAP.md` — mark K1, K2, K3 as done with commit hashes
- **Files**: `docs/adr/stream/0009-outbox-pattern.md`, `docs/REDIS-KAFKA-PRODUCTION-GAP.md` (modify)
- **Validate**: ADR follows template, references correct commit hashes

---

## Phase B: Phase 4.0–4.2 (Viewer Experience MVP) — ~3-4 days

### Reference

- [IMPLEMENTATION-PLAN.md § Phase 4](../IMPLEMENTATION-PLAN.md#phase-4--viewer-experience-) — work items 4.0–4.4
- Note: Phase 4.2 (stream viewing page) is the key milestone — the first time someone watches a stream end-to-end

### Tasks

#### Task B1: SRS Infrastructure (4.0)
- **Action**: Add `srs` service to root `compose.yaml`, verify RTMP ingest + HLS output
- **Files**: `compose.yaml` (new root file), `main/docker/srs/conf/custom.conf` (verify)
- **Validate**: `docker compose up -d srs` → publish test RTMP stream via `ffmpeg` → `curl http://localhost:8085/live/test/index.m3u8` returns playlist

#### Task B2: Browse/Discovery Page (4.1)
- **Action**: Frontend page listing live streams with category filter + search
- **Files**: `discovery.page.ts`, `discovery.page.html`, `stream-card.component.ts` (or reuse existing)
- **Validate**: Page loads, shows live streams, filters work, deep-links to channel pages

#### Task B3: Playback URL + HLS Player (4.2 + 4.3)
- **Action**: Stream viewing page with hls.js player, embedded chat panel, stream info sidebar
- **Files**: `watch.page.ts`, `watch.page.html`, `video-player.component.ts`, `play-url-response.dto.ts`, stream-service endpoint for HLS URL
- **Validate**: Open a stream → HLS player loads → chat panel connects → messages appear in real-time

#### Task B4: Viewer Presence (4.4)
- **Action**: Redis `SETEX` per viewer per room (30s TTL, refreshed on heartbeat), viewer count in UI
- **Files**: `presence.service.ts` (frontend), `ViewerPresenceController.java` (or add to existing controller)
- **Validate**: Two browser tabs → both show "2 viewers"

---

## Phase C: Quick Wins (T2 Gaps, post-Phase-4)

Small, self-contained items that close additional gaps. Can be done in any order, between larger features.

| Order | Gap | Effort | Files |
|-------|-----|--------|-------|
| C1 | **R1 — Redis Lua script** (atomic cache write) | ~2 hours | `RedisMessageCache.java` (modify), Lua script in `resources/` |
| C2 | **R2/K6 — Micrometer metrics** (cache + Kafka) | ~4 hours | `RedisMessageCache.java`, `StreamEventPublisher.java`, `application.yml` (all services) |
| C3 | **K7 — Disable auto-create topics** | ~30 min | `kafka/compose-config/broker*.yml`, topic provisioning init container |
| C4 | **K10 — Fire-and-forget lifecycle fix** | ~2 hours | `StreamService.java` — subscribe on explicit scheduler |

---

## Validation Checklist (Post-Implementation)

After both phases are complete, this end-to-end flow should work:

```
1. User A signs in → creates stream → gets publish key
2. User A pastes RTMP URL into OBS → starts publishing
3. SRS calls on_publish webhook → stream-service auto-goLive
4. Outbox: STREAM_STARTED event written in same TX as status change
5. OutboxPoller: publishes to Kafka → deletes outbox row
6. notification-service: consumes STREAM_STARTED → enqueues notification
7. User B opens discovery page → sees User A's live stream
8. User B clicks stream → HLS player loads → chat panel connects
9. User B sends a chat message → User A sees it
10. User A stops OBS → SRS calls on_unpublish → stream auto-ends
11. Outbox: STREAM_ENDED event → Kafka → notification-service
```

---

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| **Outbox poller adds ~5s latency to event delivery** | Certain | Acceptable for lifecycle events (not real-time chat). If latency matters later: Debezium CDC or Kafka Connect. |
| **Outbox table grows unbounded if Kafka is down for hours** | Low (dev) | Poller skips rows past max retries → dead-letter. Table size monitored via metrics. |
| **SRS Docker image is 500MB+** | Medium | ossrs/srs:5 is lighter than :6. Use :5 for POC. |
| **HLS latency (5-30s delay)** | Certain | Inherent to HLS. Low-latency HLS (LL-HLS) is a Phase 6 optimization. Accept standard HLS for MVP. |
| **Chat polling vs. HLS sync** | Medium | REST polling (current) has ~1-2s delay. Video has ~10-30s delay. Chat will appear ahead of video — known issue, document it, fix with WebSocket (6.4). |

---

## References

- [REDIS-KAFKA-PRODUCTION-GAP.md](../REDIS-KAFKA-PRODUCTION-GAP.md) — full gap analysis (17 gaps)
- [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) — master phase plan
- [ADR-stream-0002](../adr/stream/0002-kafka-event-publishing.md) — current at-most-once Kafka design
- [ADR-chat-0003](../adr/chat/0003-cache-staleness-on-redis-restart.md) — Redis staleness defense-in-depth
- [SERVICE-ARCHITECTURE.md](../SERVICE-ARCHITECTURE.md) — layered reactive conventions

---

*Ready for implementation. Start with Task A1 when the next session begins.*
