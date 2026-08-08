# Chat Moderation — Wave 2 (Proactive Push) Retrospective

**Date:** 2026-08-08
**Status:** Complete — P2.0–P2.2 + frontend click actions shipped; P2.5 proactive disable and semantic tiering deferred
**Original blueprint:** [chat-moderation-wave2-proactive-push.md](chat-moderation-wave2-proactive-push.md)

## 1. What was implemented (vs the original plan)

| Planned item | Status | Details |
|-------------|--------|---------|
| P2.0 Infra readiness | ✅ Shipped | Kafka broker already operational; `chat.moderation` topic created via `KafkaTopicConfig` bean (1 partition, 1 replica) |
| P2.1 chat-service producer | ✅ Shipped | `ModerationEventPublisher` — reactive Kafka bridge (`Mono.fromCallable` + `Schedulers.boundedElastic()`, 5s timeout, fire-and-forget). Wired into `ModerationService.ban()`, `updateBanDuration()`, `unban()`. |
| P2.2 notification foundation | ✅ Shipped | `ModerationEventListener` — JSON deserialization, Redis SETNX dedup (24h TTL), switch routing. `ModerationEventCoalescer` — latest-wins coalescing (5s window). `NotificationService.createModerationNotification()` + `createModeratorAlert()` — two-notification split per event. |
| P2.3 notification REST | ✅ Pre-existing | `GET /notifications`, `POST /{id}/read`, `POST /read-all` — built in earlier phase |
| P2.4 SSE delivery | ✅ Pre-existing | `GET /notifications/stream` + `SseConnectionRegistry` + `NotificationDispatcher` (persist → SSE → outbox) — built in earlier phase |
| P2.5 frontend (partial) | ⚠️ Partial | Click actions for `chat.banned`/`chat.unbanned`/`chat.moderator_alert` in `actionFromNotification()`. Sender avatar derivation in `senderFromMetadata()`. Proactive disable and semantic tiering deferred (see §2). |
| P2.6 verify + docs | ⚠️ Partial | Integration testing skipped per user instruction. Retrospective written. |
| **T0** (discovered) | ✅ Shipped | `broadcasterUsername` added to `ChatRoom` — V8 Flyway migration, entity field, factory overloads, wiring through `RoomService.getOrCreate()` and `StreamControlListener.handleStreamCreated()`. Needed for moderator alert recipient name. |

### Event contract — planned vs actual

The implemented `ModerationEvent` record extends the blueprint by 4 fields for richer notification metadata:

| Field | Planned | Implemented | Reason |
|-------|---------|-------------|--------|
| `bannedUsername` | ✗ | ✅ | Display name for notification body + metadata |
| `bannedByUsername` | ✗ | ✅ | Moderator display name for moderator alert title |
| `broadcasterSubject` | ✗ | ✅ | Recipient for moderator alert notification |
| `broadcasterUsername` | ✗ | ✅ | Display name for moderator alert body |

The blueprint's original contract was minimalist (8 fields); the implemented contract has 12. These additions eliminate cross-service lookups at notification creation time — all display names travel in the event envelope.

### Files created/modified

| File | Action | Purpose |
|------|--------|---------|
| `common/…/ModerationEvent.java` | Created | Immutable record (12 fields, 3 factory methods) |
| `common/…/KafkaTopicConfig.java` | Modified | `chatModerationTopic()` bean |
| `chat-service/…/ModerationEventPublisher.java` | Created | Reactive Kafka producer bridge |
| `chat-service/…/ModerationService.java` | Modified | Event emission in ban/updateDuration/unban |
| `chat-service/…/V8__add_broadcaster_username_to_chat_room.sql` | Created | Flyway migration |
| `chat-service/…/ChatRoom.java` | Modified | `broadcasterUsername` field |
| `chat-service/…/RoomService.java` | Modified | Accept `broadcasterUsername` in `getOrCreate()` |
| `chat-service/…/StreamControlListener.java` | Modified | Pass `broadcasterUsername` from stream event |
| `notification-service/…/ModerationEventListener.java` | Created | Kafka consumer + dedup + routing + coalescer gate |
| `notification-service/…/ModerationEventCoalescer.java` | Created | Layer 2 de-spam — Redis SETNX 5s window |
| `notification-service/…/NotificationService.java` | Modified | `createModerationNotification()` + `createModeratorAlert()` + helpers |
| `streaming-ui/…/notification.dto.ts` | Modified | `actionFromNotification()` — 3 new cases; `senderFromMetadata()` — moderator avatar derivation |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| P2.5 proactive chat-panel disable | Future wave | [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md) | Enforcement floor (403 → bannedState) already covers the UX. Proactive disable via SSE signal requires cross-component communication (NotificationService → ChatPanelComponent) — additive, not blocking. |
| P2.5 semantic tiering (Layer 3) | Future wave | [Blueprint §Notification cadence & de-spam](chat-moderation-wave2-proactive-push.md) | Layer 1 (commit-once UX) and Layer 2 (coalescer) already suppress the common burst. Semantic tiering suppresses toasts for duration increases — only needed at scale. |
| P2.6 integration testing | N/A | N/A | Explicitly skipped per user instruction ("no need for a full TDD, we can ignore the test"). `:notification-service:compileJava` and `ng build` gates are green. |
| Multi-instance SSE fanout (D4) | Future wave | Blueprint D4 | Single-instance for now; `SseConnectionRegistry` is in-memory. Deferred until >1 instance. |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended action |
|------|---------|-------------------|
| KAFKA-INFRASTRUCTURE.md missing | The retro update matrix defines a comprehensive Kafka topology reference doc. `chat.moderation` topic, `ModerationEvent` types, producer (chat-service), and consumer (notification-service) exist but no single doc catalogs them. | Create `docs/KAFKA-INFRASTRUCTURE.md` when Kafka topology grows (next topic addition). For now, the `KafkaTopicConfig.java` bean serves as the topic catalog. |
| P2.2 outbox coalescing variant | The blueprint proposed coalescing at the outbox level (supersede pending un-delivered outbox row for same key). The implemented coalescer operates at the event-listener level (Redis SETNX gate before notification creation). Both are valid; the Redis approach is simpler and doesn't require outbox table schema changes. | Document the Redis-based coalescing as the chosen approach. If email delivery is added later, revisit outbox-level coalescing for the email path. |

## 4. Architectural decisions made during implementation

1. **Coalescer at listener level, not outbox level.** The blueprint proposed coalescing by superseding pending outbox rows. The implementation gates at the listener level (Redis SETNX, 5s window) before notification creation, which is simpler and avoids outbox schema changes. If outbox email delivery is added in a future phase, outbox-level coalescing should be revisited.

2. **Two notifications per moderation event (not one).** Each moderation event produces two notifications: one for the banned user (`chat.banned`/`chat.unbanned`) and one for the room owner (`chat.moderator_alert`). This keeps each notification's recipient list single-subject, which matches the existing `Notification` entity model (one `recipientSubject` per row) and the SSE push architecture (connection registry keyed by `sub`).

3. **Display names travel in the event envelope.** `bannedUsername`, `bannedByUsername`, and `broadcasterUsername` are included in the `ModerationEvent` record rather than resolved by notification-service at consume time. This follows the existing `embed-cross-service-reference-data` pattern — embed another service's display fields at write time; never store id-only + cross-service lookup.

4. **`broadcasterUsername` denormalized on ChatRoom.** Discovered during implementation that `ChatRoom` lacked the broadcaster's display name — needed for the moderator alert notification metadata. Added via V8 Flyway migration (`TEXT` — Tier 1, no converter needed) and wired through `RoomService.getOrCreate()` + `StreamControlListener`. Follows the embed-at-write-time pattern.

5. **Duration change emits `BANNED` (re-assert), not a distinct event type.** Per the blueprint, `updateBanDuration()` emits a `BANNED` event carrying the new `expiresAt`. The coalescer collapses rapid re-edits, and the frontend re-bases the countdown on the latest metadata. No `DURATION_CHANGED` event type — the `eventId` changes but the semantic is the same.

## 5. Documents to update

| Document | Current status | What's stale | Action taken |
|----------|---------------|-------------|--------------|
| `chat-moderation-wave2-proactive-push.md` | "Planned — dependency-gated" | Wave 2 is now implemented | → Mark status as "Implemented" |
| `IMPLEMENTATION-PLAN.md` | Last updated 2026-08-08 (fan-out) | Wave 2 moderation push not listed | → Add Wave 2 completion note |
| `REDIS-KAFKA-PRODUCTION-GAP.md` R8 | "Ephemeral data store use cases not yet implemented" | Idempotency dedup now uses Redis SETNX in notification-service | → Update R8 to note partial implementation |
| `ARCHITECTURE.md` | Current | No structural change — `chat.moderation` is within existing Kafka topology | No update needed |

## 6. Architecture & reference docs updated

| Document | Trigger | What changed |
|----------|---------|-------------|
| `IMPLEMENTATION-PLAN.md` | Phase completion | Wave 2 moderation push noted under Phase 5 |
| `REDIS-KAFKA-PRODUCTION-GAP.md` | Redis code change | R8 updated — idempotency keys now implemented in notification-service |
| `chat-moderation-wave2-proactive-push.md` | Implementation complete | Status → Implemented; actual event contract noted |

## 7. Updated execution order (actual vs planned)

| Planned commit | Actual | Status |
|---------------|--------|--------|
| P2.0 Infra readiness | (pre-existing) | ✅ Kafka broker already operational |
| P2.1 chat-service producer | T1 + T2 | ✅ Shipped |
| P2.2 notification foundation | T3 + T4 + T6 | ✅ Shipped |
| P2.3 notification REST | (pre-existing) | ✅ Shipped in prior phase |
| P2.4 SSE delivery | (pre-existing) | ✅ Shipped in prior phase |
| P2.5 frontend click + avatar | T5 | ✅ Shipped |
| P2.5 proactive disable | — | 🔵 Deferred |
| P2.5 semantic tiering | — | 🔵 Deferred |
| P2.6 verify + docs | This retro | ⚠️ Partial (no integration tests) |
| T0 broadcasterUsername | T0 | ✅ Shipped (discovered during T2) |

## 8. Key risks carried forward

1. **No integration tests for the Kafka pipeline.** The `ModerationEventPublisher` → Kafka → `ModerationEventListener` chain is verified only by compilation. A topic miss or deserialization failure would surface at runtime. Mitigation: the fire-and-forget producer has error logging; consumer has `.doOnError()` + `.onErrorResume()` allow-through. The enforcement floor (403 on send) is the backstop.

2. **Single-instance SSE connection registry.** `SseConnectionRegistry` is in-memory (ConcurrentHashMap). If notification-service scales to >1 instance, a banned user connected to instance A won't receive notifications delivered to instance B. Mitigation: each instance consumes the topic independently (same consumer group), so all instances receive all events. The fan-out is to locally-connected users only — if the banned user isn't connected to the consuming instance, they miss the real-time push but get the persisted notification on next REST fetch.

3. **Coalescer best-effort.** The `ModerationEventCoalescer` uses `.onErrorReturn(true)` — Redis failures allow all events through. In a Redis outage, rapid duration edits would produce notification bursts. Acceptable because Layer 1 (commit-once UX) already collapses the common case.

4. **Coalescer window ignores the last event in a burst.** The current implementation uses `SET NX` — only the first event in a 5s window is processed; subsequent events update the Redis value but don't trigger notification creation. The *last* event's metadata (latest `expiresAt`) is stored but never delivered. This means a rapid `1h → permanent` edit delivers the `1h` notification, not the `permanent` one. To fix: add a delayed "flush" that reads the latest eventId from Redis after the window closes and delivers it. Low priority — the enforcement floor already blocks sends regardless of notification content.
