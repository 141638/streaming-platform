# Blueprint: Wave 2 — Proactive Moderation Push

**Date**: 2026-08-06
**Status**: draft
**Deciders**: hieuht, Claude

## Summary

Wave 2 extends the Wave 1 enforcement floor (reactive `403 CHAT_USER_BANNED`) with a
real-time proactive push pipeline: when a moderator bans/unbans a user, chat-service
emits a `chat.moderation` Kafka event. notification-service consumes it, persists a
notification, and pushes it via SSE to the banned user (proactive disable + toast) and
to the room owner (moderator alert in the bell dropdown). The frontend renders a
moderation card in the existing notification surface. The 403 floor remains the
independent backstop per ADR-0006 — push is strictly additive.

## Learning Objectives

| Skill | Where you'll implement it |
|-------|---------------------------|
| Kafka producer (Reactive) | `chat-service`: `ModerationEventPublisher` emitting to `chat.moderation` |
| Kafka consumer + routing | `notification-service`: `ModerationEventListener` mirroring `StreamControlListener` |
| SSE fan-out patterns | `notification-service`: push to banned user + room owner via existing `SseConnectionRegistry` |
| Event contract design | `common`: `ModerationEvent` record with factory methods |
| Notification coalescing | `notification-service`: latest-wins dedup by `(roomKey, subject)` in outbox |
| Frontend SSE consumption | `streaming-ui`: moderation card rendering in dropdown + toast, proactive disable signal |

## Patterns to Mirror

| Category | Source file | Pattern to copy |
|----------|-------------|-----------------|
| Kafka topic definition | `common/.../messaging/KafkaTopicConfig.java` | `@Bean NewTopic` method for `chat.moderation` |
| Event factory record | `common/.../messaging/StreamEvent.java` | Immutable record, factory methods, JSON-serializable |
| Producer config + KafkaTemplate | `chat-service/.../config/KafkaConsumerConfig.java` (`dlqPublisher`) | `KafkaTemplate<String, String>` bean for `chat.moderation` |
| Consumer listener structure | `notification-service/.../messaging/StreamControlListener.java` | `@KafkaListener`, `SETNX` dedup, switch routing, `.blockOptional(10s)` |
| Notification creation from event | `notification-service/.../application/NotificationService.java` (`createFromStreamEvent`) | Switch on event type, `Notification.create(...)`, `dispatcher.deliver(n)` |
| Delivery pipeline | `notification-service/.../application/NotificationDispatcher.java` | Persist → SSE push → outbox enqueue |
| SSE push | `notification-service/.../infrastructure/SseConnectionRegistry.java` | `registry.push(subject, response)` — fire-and-forget |
| SSE controller | `notification-service/.../api/NotificationSseController.java` | `@GetMapping("/notifications/stream")`, JWT-scoped, heartbeat |
| REST controller | `notification-service/.../api/NotificationController.java` | `@GetMapping("/notifications")`, cursor pagination, JWT-scoped |
| Frontend service | `streaming-ui/.../services/notification.service.ts` | `fetchEventSource`, `connect()/disconnect()`, REST pagination |
| Frontend DTO mapping | `streaming-ui/.../contracts/notification.dto.ts` | `mapNotificationResponse`, `actionFromNotification`, `severityFromCategory` |
| Frontend card component | `streaming-ui/.../notification-card/notification-card.component.ts` | `input.required<NotificationDto>()`, severity classes, relative time |

## Architecture Diagram

```
  Moderator bans User B in Room R
         │
         ▼
  chat-service ModerationService.ban()
         │  (Wave 1: persist chat_ban row — ENFORCEMENT FLOOR)
         │
         └──► ModerationEventPublisher.publish(event)        [NEW]
                   │  KafkaTemplate.send("chat.moderation", subject, json)
                   │  Key = bannedSubject (partition ordering)
                   ▼
  ┌──────────────────────────────────────────────────┐
  │  Kafka topic: chat.moderation (1 partition)      │
  │  Event: {eventId, type, roomKey, subject,        │
  │          bannedBy, bannedByUsername,             │
  │          broadcasterSubject, broadcasterUsername,│
  │          reason, expiresAt, occurredAt}          │
  └──────────────┬───────────────────────────────────┘
                 │
                 ▼
  notification-service ModerationEventListener       [NEW]
         │  @KafkaListener("chat.moderation")
         │  SETNX dedup on eventId
         │  Switch on type: BANNED / UNBANNED
         │
         ├──► Banned user notification
         │      NotificationService.createModerationNotification(event)
         │      NotificationDispatcher.deliver(notification)
         │        ├─ persist to PostgreSQL (notification table)
         │        ├─ SSE push to banned user (subject)
         │        └─ outbox enqueue
         │
         └──► Room owner notification                [NEW]
                NotificationService.createModeratorAlert(event)
                NotificationDispatcher.deliver(alert)
                  ├─ persist to PostgreSQL
                  ├─ SSE push to broadcasterSubject
                  └─ outbox enqueue

  Frontend (banned user):
    SSE "notification" event → ToastService → toast (warn severity)
    NotificationDropdownComponent: card with ban reason / expiry
    ChatPanelComponent: proactive input disable signal            [NEW]

  Frontend (room owner / moderator):
    SSE "notification" event → ToastService → toast (info severity)
    NotificationDropdownComponent: card with "X banned Y" alert   [NEW]
    Bell badge: unread count increment
```

## New Files

### Backend — common module

| File | Purpose |
|------|---------|
| `main/source/backend/common/src/main/java/com/streaming/common/messaging/ModerationEvent.java` | Immutable record for `chat.moderation` event contract. Factory methods: `banned(...)`, `unbanned(...)`, `durationChanged(...)`. Fields: `eventType`, `roomKey`, `subject`, `bannedBy`, `bannedByUsername`, `broadcasterSubject`, `broadcasterUsername`, `reason`, `expiresAt`, `eventId`, `occurredAt`. JSON-serializable via Jackson. |

### Backend — chat-service

| File | Purpose |
|------|---------|
| `main/source/backend/chat-service/src/main/java/com/streaming/chat/messaging/ModerationEventPublisher.java` | Reactive Kafka producer. `Mono<Void> publish(ModerationEvent)` using `KafkaTemplate<String, String>`. Key = `event.subject()` for partition ordering. Catches serialization errors (logs, does not propagate — event emission is best-effort; the ban row is the source of truth). Uses `Schedulers.boundedElastic()` for the blocking `kafkaTemplate.send()` call. |
| `main/source/backend/chat-service/src/main/java/com/streaming/chat/config/KafkaProducerConfig.java` | `KafkaTemplate<String, String>` bean for `chat.moderation` producer, mirroring `dlqPublisher` in `KafkaConsumerConfig`. Uses `StringSerializer` for key and value. |

### Backend — notification-service

| File | Purpose |
|------|---------|
| `main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/ModerationEventListener.java` | Kafka consumer for `chat.moderation`. Mirror of `StreamControlListener`: `@KafkaListener`, Jackson deserialization → Redis SETNX dedup (24h TTL, key: `dedup:chat.moderation:{groupId}:{eventId}` per ADR common/0003) → switch on `eventType` → handler methods. `.blockOptional(10s)`. |
| `main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/ModerationEventCoalescer.java` | Latest-wins coalescing for the `(roomKey, subject)` key. On BANNED re-assert, checks the outbox for a PENDING entry with the same aggregate key. If found with a different `expiresAt`, supersedes it (deletes old + saves new). This is layer 2 of the de-spam design. |

### Frontend

| File | Purpose |
|------|---------|
| `main/source/frontend/streaming-ui/src/app/core/services/moderation-notification.service.ts` | Bridges SSE moderation events to the chat-panel. Consumes `NotificationService` events filtered by `category === 'CHAT_MODERATION'`. Exposes a signal `activeBan$` for the chat-panel to reactively disable input. Handles semantic tiering (layer 3 of de-spam): UNBANNED unlocks input, BANNED locks input, duration-increase BANNED stays locked with silent countdown refresh. |

## Modified Files

### Backend — common module

| File | Change | Why |
|------|--------|-----|
| `main/source/backend/common/src/main/java/com/streaming/common/messaging/KafkaTopicConfig.java` | Add `@Bean NewTopic chatModerationTopic()` — `chat.moderation`, 1 partition, 1 replica | Declare the new topic; Spring Kafka Admin creates idempotently |

### Backend — chat-service

| File | Change | Why |
|------|--------|-----|
| `main/source/backend/chat-service/src/main/java/com/streaming/chat/application/ModerationService.java` | Inject `ModerationEventPublisher`; after `banRepository.save(...)` in `ban()`, chain `.then(publisher.publish(ModerationEvent.banned(...)))`. After `deleteByRoomIdAndBannedSubject` in `unban()`, chain `.then(publisher.publish(ModerationEvent.unbanned(...)))`. Wire the `// Wave 2 (ADR-0007)` emit-marker in `updateBanDuration()`: after `save(ban)`, chain `.then(publisher.publish(ModerationEvent.durationChanged(...)))`. All emissions use `.doOnError(log)` — fire-and-forget; the ban row is the source of truth. Extract `broadcasterSubject` and `broadcasterUsername` from the loaded `ChatRoom`. | Wire producer into the ban/unban/duration-change lifecycle |
| `main/source/backend/chat-service/src/main/java/com/streaming/chat/domain/ChatRoom.java` | Verify `getBroadcasterUsername()` or equivalent accessor exists for the event payload, or add it if missing | Need broadcaster username for notification display text |

### Backend — notification-service

| File | Change | Why |
|------|--------|-----|
| `main/source/backend/notification-service/src/main/java/com/streaming/notification/application/NotificationService.java` | Add `createModerationNotification(ModerationEvent)` method — creates a `Notification` for the banned user with category `CHAT_MODERATION`, action `chat.banned`/`chat.unbanned`, metadata JSON carrying `roomKey`, `reason`, `expiresAt`, `bannedByUsername`. Add `createModeratorAlert(ModerationEvent)` method — creates a `Notification` for the broadcaster/room-owner with category `CHAT_MODERATION`, action `chat.moderator_alert`, metadata carrying the same fields plus `bannedBySubject`. | New notification creation paths for moderation events |
| `main/source/backend/notification-service/src/main/java/com/streaming/notification/config/KafkaConsumerConfig.java` | No structural change — the DLQ handler already covers all consumers. Add `@Value` for `CHAT_MODERATION_TOPIC:chat.moderation` if used in the listener for configurability. | Topic name configurability |
| `main/source/backend/notification-service/src/main/java/com/streaming/notification/config/SecurityConfig.java` | No changes — all notification endpoints are JWT-scoped to the authenticated user. The SSE stream already requires authentication. | Moderation push reuses existing auth |

### Frontend

| File | Change | Why |
|------|--------|-----|
| `main/source/frontend/streaming-ui/src/app/core/contracts/notification.dto.ts` | Add cases to `actionFromNotification()`: `chat.banned` / `chat.unbanned` → `{ type: 'none' }` (banned user can't navigate; just informational). `chat.moderator_alert` → parse `roomKey` from metadata, return `{ type: 'navigate', route: '/watch/{roomKey}' }` for room owner. Add `sender` derivation from metadata for `CHAT_MODERATION` notifications: extract `bannedByUsername` / `bannedUsername` from metadata, construct `NotificationSender` with DiceBear avatar URL. | Moderation notification rendering and navigation |
| `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-card/notification-card.component.html` | Add conditional rendering for `CHAT_MODERATION` cards: show `sender` avatar + username, ban reason in body, expiry countdown for temp bans using `relativeTime` + "expires" label. For room-owner alerts: show "X banned Y" with both avatars. | Moderation-specific card UI |
| `main/source/frontend/streaming-ui/src/app/shared/organisms/chat-panel/chat-panel.component.ts` | Inject `ModerationNotificationService`; subscribe to `activeBan$` signal; when non-null, disable the chat input and show the "You are banned" banner (same banner already rendered on `403` from Wave 1 — now also rendered proactively on SSE BANNED event). On UNBANNED, re-enable input. | Proactive input disable (mid-session push) |

## Tasks

### Task 0: Prerequisite Verification

- **Action**: Verify Kafka broker is owned and reachable (ADR-0007 gate). Verify notification-service foundation is complete (ADR-0000 accepted, REST + SSE operational).
- **Files**: `main/docker/kafka/single-broker/docker-compose.yaml`, `docs/adr/notification/0000-architecture-foundation.md`
- **Validate**: `docker compose up -d kafka` succeeds; `kafka-topics --bootstrap-server localhost:9094 --list` works; notification-service compiles and passes tests.

### Task 1: Event Contract + Topic (common)

- **Action**: Create `ModerationEvent` record in `common` module. Add `chat.moderation` topic bean to `KafkaTopicConfig`.
- **Files**:
  - **Creates**: `main/source/backend/common/src/main/java/com/streaming/common/messaging/ModerationEvent.java`
  - **Modifies**: `main/source/backend/common/src/main/java/com/streaming/common/messaging/KafkaTopicConfig.java`
- **Contract**:
  ```java
  public record ModerationEvent(
      String eventType,    // "BANNED" | "UNBANNED"
      String roomKey,
      String subject,      // banned user's JWT sub (partition key)
      String bannedBy,     // moderator's JWT sub
      String bannedByUsername,
      String broadcasterSubject,
      String broadcasterUsername,
      String reason,       // nullable
      String expiresAt,    // ISO-8601 or null (= permanent)
      String eventId,      // UUID for consumer dedup
      String occurredAt    // ISO-8601
  ) {
      public static ModerationEvent banned(...) { ... }
      public static ModerationEvent unbanned(...) { ... }
      public static ModerationEvent durationChanged(...) { ... }
  }
  ```
- **Validate**: `./gradlew :common:compileJava` passes. New topic appears in Kafka UI after restart.

### Task 2: chat-service Producer

- **Action**: Create `KafkaProducerConfig` (KafkaTemplate bean) and `ModerationEventPublisher`. Wire into `ModerationService.ban()`, `unban()`, `updateBanDuration()`.
- **Files**:
  - **Creates**: `main/source/backend/chat-service/src/main/java/com/streaming/chat/config/KafkaProducerConfig.java`
  - **Creates**: `main/source/backend/chat-service/src/main/java/com/streaming/chat/messaging/ModerationEventPublisher.java`
  - **Modifies**: `main/source/backend/chat-service/src/main/java/com/streaming/chat/application/ModerationService.java`
- **Design notes**:
  - `ModerationEventPublisher.publish(ModerationEvent)` returns `Mono<Void>`. Uses `Mono.fromCallable(() -> kafkaTemplate.send(...)).subscribeOn(Schedulers.boundedElastic())` to bridge blocking `KafkaTemplate` with the reactive chain. Errors are logged + swallowed — event emission is best-effort; the ban row in PostgreSQL is authoritative.
  - `ban()` needs: `roomKey` (param), `targetSubject` (param), `bannedByUsername` (param), `broadcasterSubject` (from loaded `ChatRoom`), `broadcasterUsername` (from loaded `ChatRoom`).
  - `unban()` needs the same set. Restructure to load the room first, then perform the delete + publish.
  - `updateBanDuration()`: wire after `banRepository.save(ban)` in the `.flatMap` chain.
- **Validate**: `./gradlew :chat-service:test --tests "*ModerationServiceTest"`. Integration: create a ban via REST, check Kafka UI for `chat.moderation` message.

### Task 3: notification-service Consumer

- **Action**: Create `ModerationEventListener` consuming `chat.moderation`. Mirror `StreamControlListener` structure exactly.
- **Files**:
  - **Creates**: `main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/ModerationEventListener.java`
  - **Creates**: `main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/ModerationEventCoalescer.java`
- **Listener structure** (mirrors StreamControlListener):
  - `@KafkaListener` on `chat.moderation`
  - Jackson deserialization → Redis SETNX dedup (24h TTL, key: `dedup:chat.moderation:{groupId}:{eventId}`)
  - Switch on `eventType`: BANNED → `handleBanned()`, UNBANNED → `handleUnbanned()`
  - `.onErrorResume` → log + process anyway (Redis down = duplicate > dropped)
  - `.blockOptional(Duration.ofSeconds(10))`
- **Coalescer**: Layer 2 of de-spam. On BANNED re-assert for same `(roomKey, subject)`, queries outbox for PENDING entry with matching aggregate key. If found and `expiresAt` differs, supersedes (deletes old + saves new).
- **Validate**: `./gradlew :notification-service:test --tests "*ModerationEventListener*"`. Publish a test event directly to Kafka, verify notification rows appear in PostgreSQL.

### Task 4: NotificationService Unification

- **Action**: Add `createModerationNotification` and `createModeratorAlert` methods to `NotificationService`.
- **Files**: **Modifies** `main/source/backend/notification-service/src/main/java/com/streaming/notification/application/NotificationService.java`
- **Banned user notification**: Category `CHAT_MODERATION`, action `chat.banned`/`chat.unbanned`, recipient = `event.subject()`
- **Moderator alert**: Category `CHAT_MODERATION`, action `chat.moderator_alert`, recipient = `event.broadcasterSubject()`
- **Metadata JSON** (shared):
  ```json
  {
    "roomKey": "room-abc",
    "reason": "spamming",
    "expiresAt": "2026-08-07T00:00:00Z",
    "bannedByUsername": "moderator",
    "bannedBySubject": "sub:mod-1",
    "bannedUsername": "troublemaker",
    "bannedSubject": "sub:user-2"
  }
  ```
- **Validate**: `./gradlew :notification-service:test --tests "*NotificationServiceTest*"`.

### Task 5: Frontend Moderation Card + Service

- **Action**: Extend `notification.dto.ts` mapping helpers for moderation actions. Create `ModerationNotificationService` for proactive disable. Update `notification-card` template for moderation-specific display.
- **Files**:
  - **Modifies**: `main/source/frontend/streaming-ui/src/app/core/contracts/notification.dto.ts`
  - **Creates**: `main/source/frontend/streaming-ui/src/app/core/services/moderation-notification.service.ts`
  - **Modifies**: `main/source/frontend/streaming-ui/src/app/shared/molecules/notification-card/notification-card.component.html`
  - **Modifies**: `main/source/frontend/streaming-ui/src/app/shared/organisms/chat-panel/chat-panel.component.ts`
- **Semantic tiering** (Layer 3 of de-spam):
  - BANNED (initial): lock input, show toast
  - BANNED (duration increase): stay locked, silent countdown refresh, NO toast
  - BANNED (duration reduction): stay locked, optional gentle toast
  - UNBANNED: unlock input, show toast
- **Validate**: `npm run build` passes.

### Task 6: Anti-Spam / Rate Limiting Integration

- **Action**: Finalize the three-layer de-spam design. Integrate and verify Layers 1-3 end-to-end.
- **Design verification**:
  - **Layer 1** (SHIPPED): Commit-once duration editor — one PATCH = one event
  - **Layer 2** (Task 3): `ModerationEventCoalescer` — latest-wins by `(roomKey, subject)`
  - **Layer 3** (Task 5): `ModerationNotificationService` — semantic tiering for toasts
- **Validate**: Send 3 rapid duration-change events. Verify only 1 notification persists, only 1 SSE push fires for banned user, no toast spam on duration increase. `ng build` passes.

### Task 7: Integration Testing + Docs

- **Action**: Write end-to-end verification flow. Update ADR-0007 status to "accepted (implemented)".
- **Files**: **Modifies** `docs/adr/chat/0007-proactive-push-infrastructure-gated.md`
- **E2E flow** (manual verification):
  1. Start Kafka + all services. Create room. Two browser tabs: Tab A (room owner) and Tab B (viewer).
  2. Tab A bans Tab B with reason "spamming" and 1h duration.
     - **Verify**: Tab B sees toast, input disables proactively, notification card in dropdown. Tab A sees toast + notification card + bell badge.
  3. Tab A edits ban duration to 24h.
     - **Verify**: Tab B sees silent countdown update (no new toast). Tab A sees no new toast.
  4. Tab A edits ban duration to 10min (reduction).
     - **Verify**: Tab B sees optional gentle toast "ban shortened to 10 minutes".
  5. Tab A unbans.
     - **Verify**: Tab B sees "ban lifted" toast, input re-enables. Tab A sees alert.
  6. Tab A bans again (permanent).
     - **Verify**: Tab B sees permanent ban banner (no countdown).
  7. Check PostgreSQL: notification + notification_outbox rows for both recipients.
  8. Check Redis: dedup keys exist with 24h TTL.
- **Validate**: `./gradlew :chat-service:test :notification-service:test` green. `npm run build` green.

## Data Flow

### BAN flow (happy path)

```
1. Moderator clicks "Ban" in chat-panel drawer
2. POST /api/chat/v1/rooms/{roomKey}/bans → gateway → chat-service
3. ModerationService.ban(jwt, roomKey, targetSubject, ...)
   a. authorizedRoom(jwt, roomKey) — loads ChatRoom, enforces PBAC
   b. Delete any prior ban for (roomId, targetSubject)
   c. Save new ChatBan with expiresAt = now + durationSeconds
   d. ModerationEventPublisher.publish(ModerationEvent.banned(...))
      - KafkaTemplate.send("chat.moderation", targetSubject, JSON)
      - Key = targetSubject (partition by banned user for ordering)
      - Fire-and-forget: .doOnError(log) — ban row is authoritative
4. Kafka broker persists and delivers to notification-service consumer group
5. ModerationEventListener.onModerationEvent(payload)
   a. Deserialize ModerationEvent from JSON
   b. SETNX dedup:chat.moderation:{groupId}:{eventId} TTL 24h
   c. handleBanned(event):
      - ModerationEventCoalescer.coalesce(event)
      - Mono.when(
          createModerationNotification(event),  // for banned user
          createModeratorAlert(event)           // for room owner
        )
      - Each notification → dispatcher.deliver():
        i.   repository.save → PostgreSQL notification table
        ii.  sseRegistry.push → SSE frame to connected client
        iii. outboxService.enqueue → notification_outbox table
6. SSE delivers "event: notification" frame to connected clients
7. Frontend NotificationService.onmessage():
   - Parse JSON → NotificationResponseDto → NotificationDto
   - ToastService → toast appears
   - refreshUnreadCount() → bell badge updates
8. ModerationNotificationService (chat-panel-scoped):
   - Detects CHAT_MODERATION event for current user + current room
   - Sets activeBan signal → input disables, "You are banned" banner shows
   - Semantic tiering: initial ban = toast; duration increase = silent
```

### Temp-Ban Expiry (NO event — lazy, per ADR-0006)

```
1. Ban expires naturally (expiresAt passes wall clock)
2. No Kafka event emitted (design decision: lazy expiry, ADR-0006 §2)
3. Server side: BanSendGuard.isActive(now) → false on next send attempt
4. Client side: ChatModerationService._now tick (30s interval) →
   activeBans computed filters out the expired ban
5. Chat-panel: input re-enables automatically (client-side expiry, no server push needed)
```

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `KafkaTemplate.send()` blocks reactive chain | Medium | `Mono.fromCallable().subscribeOn(Schedulers.boundedElastic())` with 5s timeout. Fire-and-forget — ban row is authoritative; event loss is acceptable for this non-critical push path. |
| Duplicate bans of same user create multiple events | Low | `ModerationService.ban()` deletes-then-inserts — only one active row per `(roomId, bannedSubject)`. Consumer-side eventId dedup (SETNX) handles redelivery. |
| Rapid duration edits produce notification burst | Low | Three-layer de-spam: commit-once UX (shipped) + `ModerationEventCoalescer` (Task 3) + semantic tiering (Task 5). Each layer is independent defense-in-depth. |
| EventId dedup across notification-service instances | Low | Redis SETNX is global — all instances share the same Redis. Key format: `dedup:chat.moderation:{groupId}:{eventId}` per ADR common/0003. |
| Gateway `response-timeout: -1` for SSE route drifts on gateway refactor | Low | Already configured in `gateway-service/application.yml`. Documented in ADR-0008 risk table. |
| chat-service producer needs Kafka broker (timeout on first send if broker down) | Medium | Producer is fire-and-forget. If broker unreachable, `kafkaTemplate.send()` times out after `max.block.ms` (default 60s). Wrap with `.timeout(Duration.ofSeconds(5))` to bound the wait. Ban row already persisted — event loss degrades gracefully to the Wave 1 reactive floor. |
| `broadcasterUsername` is null for rooms created before the field existed | Low | Fall back to truncated `broadcasterSubject`. The subject is sufficient to generate a DiceBear avatar URL on the frontend. |
| Frontend `moderation-notification.service` races with SSE connect on page load | Low | The service subscribes after SSE is connected (same pattern as `NotificationDropdownComponent.ngOnInit()`). The REST catch-up (`GET /v1/notifications`) fills any gap. |

## Validation

```bash
# === Prerequisite checks ===
docker compose -f main/docker/kafka/single-broker/docker-compose.yaml exec kafka \
  kafka-topics --bootstrap-server localhost:9094 --list

cd main/source/backend && ./gradlew :notification-service:compileJava

# === Task 1: Common module ===
cd main/source/backend && ./gradlew :common:compileJava

# === Task 2: chat-service producer ===
./gradlew :chat-service:test --tests "*ModerationServiceTest"
docker compose exec kafka \
  kafka-topics --bootstrap-server localhost:9094 --list | grep chat.moderation

# === Task 3: notification-service consumer ===
./gradlew :notification-service:test --tests "*ModerationEventListener*"

# === Task 4: NotificationService ===
./gradlew :notification-service:test --tests "*NotificationServiceTest*"

# === Task 5: Frontend ===
cd main/source/frontend/streaming-ui && npm run build

# === Manual E2E: Publish test event directly to Kafka ===
docker compose exec kafka bash -c 'echo "{\"eventType\":\"BANNED\",\"roomKey\":\"test-room\",\"subject\":\"user-2\",\"bannedBy\":\"user-1\",\"bannedByUsername\":\"moderator\",\"broadcasterSubject\":\"user-1\",\"broadcasterUsername\":\"streamer\",\"reason\":\"spam\",\"expiresAt\":null,\"eventId\":\"$(uuidgen)\",\"occurredAt\":\"2026-08-06T00:00:00Z\"}" | kafka-console-producer --bootstrap-server localhost:9094 --topic chat.moderation'

# Verify notification persisted
curl -H "Authorization: Bearer <jwt>" \
  http://localhost:8080/api/notifications/v1/notifications | jq .

# === Full suite ===
cd main/source/backend && ./gradlew :chat-service:test :notification-service:test
cd main/source/frontend/streaming-ui && npm run build
```

## Key Architecture Decisions

1. **Event contract: `ModerationEvent` in `common` module** — Mirrors the `StreamEvent.java` pattern (immutable record, factory methods, JSON-serializable). Carries both `broadcasterSubject` and `broadcasterUsername` so notification-service can create a moderator alert without a cross-service lookup.

2. **Producer: fire-and-forget, not transactional outbox** — The `ModerationEventPublisher` wraps `KafkaTemplate.send()` in `Mono.fromCallable().subscribeOn(Schedulers.boundedElastic())` with error logging. The ban row in PostgreSQL is the source of truth; event loss degrades gracefully to the Wave 1 reactive floor.

3. **Consumer: mirrors `StreamControlListener` exactly** — Same `@KafkaListener` + Jackson deserialization + Redis SETNX dedup + switch routing + `.blockOptional(10s)` pattern. Any developer who understands one consumer understands the other.

4. **Two notification recipients, not just the banned user** — Each ban/unban event creates notifications for both the banned user AND the room owner (broadcaster). Platform-level moderator fan-out is deferred (notification-service has no way to enumerate all users with `chat:moderation:*` entitlement).

5. **Three-layer de-spam** — Commit-once UX (Layer 1, shipped) + `ModerationEventCoalescer` (Layer 2, latest-wins by `(roomKey, subject)`) + semantic tiering (Layer 3, frontend suppresses toasts for duration increases).

6. **No temp-ban-expiry event** — As decided in ADR-0006, temp-ban expiry is lazy on both ends (server `BanSendGuard.isActive` + client `now`-tick). Only manual early unban emits an `UNBANNED` event.

7. **All delivery infrastructure already exists** — SSE pipe, delivery pipeline, `CHAT_MODERATION` category enum, frontend notification surface (toast + bell + card + `fetchEventSource`) are all already built and operational. Wave 2 adds the Kafka event spine and moderation-specific rendering.
