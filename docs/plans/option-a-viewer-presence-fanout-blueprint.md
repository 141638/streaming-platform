# Blueprint: Option A — Viewer Presence + Follower Fan-out + Dedup Scoping

**Date**: 2026-07-17
**Status**: Plan — awaiting approval
**Branch**: develop

## Summary

Four tasks that close out Phase 4 (viewer experience) and Phase 5 (notifications), plus lay the foundation for viewer analytics:

| # | Task | Effort | Phase |
|---|------|--------|-------|
| A1 | Viewer count display (SSE push + watch page + session cards) | Small | 4.4 |
| A2 | Follower fan-out (notify followers on stream start) | Medium | 5.2b |
| A3 | Dedup key scoping (ADR common/0003 compliance) | Tiny | 5.2b |
| A4 | Heartbeat harvest service + ADR (time-series viewer data) | Medium | 4.4 → 7 |

A1 is ~80% backend-complete. A2 is ~60% complete. A3 is a one-line change. A4 is net-new but architecturally identical to existing `ViewCountFlushService`.

## Architecture Decisions (from discussion)

| Decision | Outcome | Rationale |
|----------|---------|-----------|
| SSE connection consolidation | **Deferred to Phase 6.4** | WebSocket unification handles all signals (chat, heartbeat, notifications, stream events) in one connection. Current dual SSE + REST polling is acceptable for POC scale. |
| REST heartbeats at scale | **Keep for now, migrate to WebSocket in 6.4** | `PresenceService` is cleanly abstracted — migration is a one-line change from `interval().pipe(switchMap(() => http.post(...)))` to a WS frame. |
| Heartbeat harvesting owner | **stream-service** | Owns the Redis presence keys; `ViewCountFlushService` already demonstrates the Redis→PostgreSQL flush pattern. insight-service consumes aggregated data when it comes online. |
| WebSocket design | **Sketched for 6.4** (see appendix) | Single bidirectional WS per user: FE produces chat + heartbeat, BE produces stream status + chat + notifications. Replaces 2 SSE + 1 polling connection. |

---

## Discovery: What's Already Built

### A1 — Viewer Presence: Existing Infrastructure

| Layer | Component | Status | Notes |
|-------|-----------|--------|-------|
| Backend REST | `POST /v1/streams/{id}/heartbeat` | ✅ Done | `StreamService.sendHeartbeat()` — Redis `SET key "1"` with 30s TTL, self-view exclusion |
| Backend REST | `GET /v1/streams/{id}/viewers` | ✅ Done | `StreamService.getViewerCount()` — Redis `SCAN stream:presence:{id}:*` → count |
| Backend SSE | `GET /v1/streams/events?streamId=` | ✅ Done | `StreamSseController` + `SseConnectionRegistry` with per-stream viewer tracking |
| Backend DTO | `StreamSseEvent(type, streamId, status, viewerCount)` | ✅ Done | `viewerCount` field already exists |
| Backend Registry | `SseConnectionRegistry.pushToStreamViewers()` | ✅ Done | Used for `stream:ended` broadcasts; reusable for viewer count |
| Frontend Service | `PresenceService` | ✅ Done | 15s heartbeat + 10s polling, `viewerCount: BehaviorSubject<number>` |
| Frontend Service | `StreamSseService` | ✅ Done | Already handles `stream:viewers` event type in message router |
| Frontend Service | `StreamService.sendHeartbeat()` / `getViewerCount()` | ✅ Done | HTTP methods in stream.service.ts |
| Frontend Page | `WatchPage` calls `presenceService.start(id)` | ✅ Done | `ngOnInit` starts, `ngOnDestroy` stops |

**What's missing (A1):**
1. **Backend**: No code pushes `stream:viewers` SSE events — DTO field and frontend handler exist but nothing emits them
2. **Frontend watch page**: Template doesn't display `presenceService.viewerCount` — data is available but not rendered
3. **Frontend session cards**: `StreamSummaryResponseDto` has `views` (persisted) but no live `viewerCount`
4. **Backend DTO**: `StreamSummaryResponse` needs a `viewerCount` field for browse/channel pages

### A2 — Follower Fan-out: Existing Infrastructure

| Layer | Component | Status | Notes |
|-------|-----------|--------|-------|
| Repository | `ReactiveSubscriptionRepository.findByTargetTypeAndTargetIdAndActiveTrue()` | ✅ Done | `target_type = "CHANNEL"`, `target_id = broadcasterSubject` |
| Dispatcher | `NotificationDispatcher.deliverToMany(List<Notification>, int concurrency)` | ✅ Done | `Flux.fromIterable().flatMap(this::deliver, concurrency).then()` |
| Service | `NotificationService.createFromStreamEvent()` | ✅ Done | Creates broadcaster self-notification only |
| Listener | `StreamControlListener.onStreamStarted()` | ✅ Done | Insertion point for fan-out |
| Domain | `Subscription` entity with `target_type` + `target_id` | ✅ Done | Polymorphic follow target |
| Domain | `Notification.create()` factory | ✅ Done | recipient, category, action, title, body, metadata, createdAt |
| ADR | ADR-0001 §4 — two-step fan-out design | ✅ Done | Subscription lookup → preference filter → dispatch |
| ADR | ADR-0002 §4 — inline vs outbox-driven fan-out | ✅ Done | Inline for MVP, outbox at scale |

**What's missing (A2):**
1. Fan-out logic in `onStreamStarted()` — currently only notifies the broadcaster
2. Follower-specific notification body — current body says "Your stream is now live"; followers need "{username} is now live"
3. `broadcasterUsername` on `StreamEvent` — has `broadcasterSubject` (UUID) but no display name

### A3 — Dedup Key Scoping

| Component | Current | Required |
|-----------|---------|----------|
| Dedup key | `dedup:stream-event:{eventId}` | `dedup:{topic}:{consumerGroupId}:{eventId}` |
| Topic | Already injected via `@Value` | — |
| Consumer group ID | Available via `@Value` | Needs injection |

### A4 — Heartbeat Harvesting: Net-new

Nothing built yet. Pattern to mirror: `ViewCountFlushService` (Redis SCAN → PostgreSQL batch INSERT).

---

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| SSE push | `StreamService.handlePublish()` line 794-795 | `sseRegistry.pushToStreamViewers(streamId, event)` |
| SSE event DTO | `StreamSseEvent` record | `record(String type, UUID streamId, String status, Long viewerCount)` |
| Scheduled Redis scan | `ViewCountFlushService` | `@Scheduled` + `redisTemplate.scan(ScanOptions)` + batch processing |
| Redis key pattern | `StreamService.PRESENCE_KEY_PREFIX` | `stream:presence:{streamId}:{viewerSubject}` with 30s TTL |
| Frontend SSE | `StreamSseService.connect()` | `fetchEventSource` + `runOutsideAngular` + typed event routing |
| Frontend polling | `PresenceService` | `interval().pipe(switchMap())` + `BehaviorSubject` |
| Fan-out dispatch | `NotificationDispatcher.deliverToMany()` | `Flux.fromIterable().flatMap(this::deliver, concurrency).then()` |
| Subscription query | `SubscriptionService.getActiveSubscribers()` | `findByTargetTypeAndTargetIdAndActiveTrue("CHANNEL", broadcasterSubject)` |
| Notification factory | `Notification.create()` | Static factory: recipient, category, action, title, body, metadata, createdAt |
| Dedup pattern | `StreamControlListener` line 67-69 | `redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL)` |
| Config properties | `ViewCountProperties` record | `@ConfigurationProperties(prefix = "streaming.view-count")` |
| Angular service | `PresenceService` | `@Injectable({ providedIn: 'root' })` + `inject()` + RxJS intervals |
| Angular template | `watch.page.html` | `@if` / `@else` control flow, `class` attributes over SCSS |
| Watch page component | `watch.page.ts` | `signal()` + `computed()` + `OnPush` + `takeUntilDestroyed()` |
| Flyway migration | `V1-V13` stream, `V1-V6` chat | Versioned SQL with IF NOT EXISTS |
| R2DBC batch insert | `ViewCountFlushService.insertBatch()` | `databaseClient.sql().bind().then()` in a reactive chain |

---

## Tasks

### A1: Viewer Count Display

#### A1.1 — Backend: Periodic SSE push of viewer counts

**Why**: The `stream:viewers` SSE event type is documented and the DTO field exists, but nothing emits these events. SSE push is more efficient than 10s polling.

**What**: Create a `ViewerCountPushService` that periodically scans active streams and pushes viewer counts via SSE.

**Design decisions**:
- `@Scheduled(fixedRate = 10_000)` — matches the current frontend poll interval
- Scan Redis for `stream:presence:*` keys, group by stream ID, count per stream
- Push `StreamSseEvent("stream:viewers", streamId, null, count)` via `sseRegistry.pushToStreamViewers()`
- Only process streams that have connected viewers in the SSE registry (`getActiveStreamIds()`)
- If no Redis keys found for a stream, push `viewerCount = 0`

**Files**:

| File | Action | Why |
|------|--------|-----|
| `stream-service/.../sse/SseConnectionRegistry.java` | Modify | Add `getActiveStreamIds(): Set<UUID>` — returns unmodifiable view of `streamViewers.keySet()` |
| `stream-service/.../service/ViewerCountPushService.java` | **New** | `@Scheduled(fixedRate=10000)` — SCANs `stream:presence:*`, groups by streamId, pushes via SSE |
| `stream-service/.../service/StreamService.java` | Modify | Add `getPresenceKeyStreamIds(): Flux<UUID>` — SCANs presence keys and extracts stream UUIDs for the push service |

**Validate**: Start a stream → open watch page in two browsers → `docker logs stream-service` shows `stream:viewers` events every 10s with correct count

---

#### A1.2 — Frontend: Viewer count on watch page

**Why**: `PresenceService.viewerCount` BehaviorSubject is already populated but not displayed.

**What**: Add a viewer count badge to the `watch-info-bar` overlay. Switch from polling to SSE-driven updates while keeping polling as fallback.

**Files**:

| File | Action | Why |
|------|--------|-----|
| `frontend/.../pages/watch/watch.page.ts` | Modify | Expose `viewerCount` signal derived from `presenceService.viewerCount` |
| `frontend/.../pages/watch/watch.page.html` | Modify | Add viewer count pill in `watch-info-bar` after status badge |

**Template sketch** (in `watch-info-bar`, after `app-stream-status-badge`):
```html
@if (isLive()) {
  <span class="flex align-items-center gap-1 text-sm text-white">
    <i class="pi pi-eye text-xs"></i>
    <span>{{ viewerCount() | number }}</span>
  </span>
}
```

Plus wire `streamViewers$` from `StreamSseService` to update `PresenceService.viewerCount` so SSE pushes take priority over polling.

**Files (additional)**:

| File | Action | Why |
|------|--------|-----|
| `frontend/.../pages/watch/watch.page.ts` | Modify | Subscribe to `streamSseService.streamViewers$` and feed into presence count |

**Validate**: Open watch page for live stream → viewer count visible → count increments when second browser opens → count drops when second browser closes

---

#### A1.3 — Frontend + Backend: Live viewer count on session cards

**Why**: Browse page and channel page session cards show `views` (persisted unique count) but not live concurrent viewer count.

**What**: Add `viewerCount` to `StreamSummaryResponse` (backend), computed from Redis presence keys at query time. Display live-viewer pill overlay on session card thumbnails.

**Backend**:

| File | Action | Why |
|------|--------|-----|
| `stream-service/.../dto/StreamSummaryResponse.java` | Modify | Add `Long viewerCount` field |
| `stream-service/.../dto/LiveStreamPageResponse.java` | Modify | Add `viewerCount` if not already present |
| `stream-service/.../service/StreamService.java` | Modify | In `listLiveStreams()` and channel session queries, batch-fetch viewer counts via `getViewerCount()` and merge into responses |

**Frontend**:

| File | Action | Why |
|------|--------|-----|
| `frontend/.../contracts/stream-summary-response.dto.ts` | Modify | Add `viewerCount: number` |
| `frontend/.../organisms/session-rail/session-rail.component.html` | Modify | Add live-viewer pill overlay on thumbnail when status is LIVE |
| `frontend/.../organisms/session-rail/session-rail.component.ts` | Modify | Expose `isLive` computed from status |

**Validate**: Browse page shows live viewer counts on LIVE stream cards → page refresh updates counts → channel page session rail shows same

---

### A2: Follower Fan-out

#### A2.1 — Dependency: Add `broadcasterUsername` to StreamEvent

**Why**: Follower notification body should say "{username} is now live", not "Stream {uuid} is now broadcasting".

**Design decision**: Add `String broadcasterUsername` to the common `StreamEvent` record. Java records with nullable reference fields are backward-compatible with JSON deserialization — chat-service ignores unknown/missing fields. Stream-service already has `broadcasterUsername` denormalized on the entity.

**Files**:

| File | Action | Why |
|------|--------|-----|
| `common/.../messaging/StreamEvent.java` | Modify | Add `String broadcasterUsername` field |
| `stream-service/.../messaging/StreamEventPublisher.java` | Modify | Populate `broadcasterUsername` from entity in all event-building methods |
| `stream-service/.../service/StreamService.java` | Modify | Pass username through to publisher calls (goLive, end, cancel, create, handlePublish) |

**Validate**: `./gradlew :common:compileJava :stream-service:compileJava :chat-service:compileJava :notification-service:compileJava` — all compile

---

#### A2.2 — Backend: Fan-out in StreamControlListener

**Why**: When a streamer goes live, followers should be notified. The subscription system and dispatcher batch method already exist — only the wiring is missing.

**What**: Modify `StreamControlListener.onStreamStarted()` to fan out to followers in addition to the broadcaster self-notification.

**Design decisions**:
- **Inline fan-out for MVP** (per ADR-0002 §4). Outbox-driven `FanOutJob` deferred until subscriber counts warrant it.
- **Concurrency**: 8 concurrent `deliver()` calls on bounded elastic
- **Skip preference filtering for MVP**: Two-step delivery (subscription → preference check) from ADR-0001 §4 is deferred. All followers get all channels (SSE + persist + outbox).
- **Empty follower list is a no-op** — `collectList()` returns empty, `deliverToMany()` skipped

**Files**:

| File | Action | Why |
|------|--------|-----|
| `notification-service/.../messaging/StreamControlListener.java` | Modify | Inject `SubscriptionService` + `NotificationDispatcher`, add fan-out in `onStreamStarted()` |
| `notification-service/.../application/NotificationService.java` | Modify | Add `createForFollower(StreamEvent, String followerSubject)` factory method |

**New method on NotificationService**:
```java
public Mono<Notification> createForFollower(StreamEvent event, String followerSubject) {
    Notification n = Notification.create(
            followerSubject,
            NotificationCategory.STREAM_LIVE,
            "stream.started",
            event.broadcasterUsername() + " is now live",
            "Stream " + event.streamId() + " started broadcasting.",
            "{\"streamId\":\"" + event.streamId() + "\"}",
            OffsetDateTime.now(ZoneOffset.UTC));
    return Mono.just(n);
}
```

**Fan-out flow** (revised `onStreamStarted()`):
```java
private Mono<Void> onStreamStarted(StreamEvent event) {
    // 1. Broadcaster self-notification (existing behavior)
    Mono<Void> broadcasterNotification = notificationService.createFromStreamEvent(event);

    // 2. Fan-out to followers
    Mono<Void> fanOut = subscriptionService
        .getActiveSubscribers("CHANNEL", event.broadcasterSubject())
        .flatMap(sub -> notificationService.createForFollower(event, sub.getSubscriberSubject()))
        .collectList()
        .flatMap(notifications -> {
            if (notifications.isEmpty()) {
                log.debug("No followers to notify: broadcaster={}", event.broadcasterSubject());
                return Mono.empty();
            }
            log.info("Fan-out to {} followers: broadcaster={}",
                    notifications.size(), event.broadcasterSubject());
            return dispatcher.deliverToMany(notifications, 8);
        });

    return broadcasterNotification.then(fanOut);
}
```

**Validate**: User A follows User B → User B starts a stream → User A receives SSE notification toast + sees it in bell dropdown

---

### A3: Dedup Key Scoping

#### A3.1 — Scope dedup key per ADR common/0003

**Why**: Current key `dedup:stream-event:{eventId}` is unscoped. Any future consumer of `stream.control` adding Redis SETNX would collide.

**What**: Build key as `dedup:{topic}:{consumerGroupId}:{eventId}`.

**Files**:

| File | Action | Why |
|------|--------|-----|
| `notification-service/.../messaging/StreamControlListener.java` | Modify | Replace static `DEDUP_PREFIX` with dynamically-built scoped key |

**Change**:
```java
// Remove (line 35):
private static final String DEDUP_PREFIX = "dedup:stream-event:";

// Add:
@Value("${spring.kafka.consumer.group-id}")
private String consumerGroupId;
// topic field already exists (lines 42-43)

// In onStreamControl(), replace line 67:
String dedupKey = "dedup:" + topic + ":" + consumerGroupId + ":" + event.eventId();
```

**Validate**: `./gradlew :notification-service:compileJava`. Log statement confirms scoped format: `dedup:stream.control:notification-consumer:evt_abc123`

---

### A4: Heartbeat Harvest Service (Time-Series Viewer Analytics)

**Why**: The 30s presence heartbeat data in Redis is currently ephemeral — it vanishes after TTL. We need durable time-series data for:
- Real-time viewer count charts on the stream dashboard
- Historical viewer analytics in insight-service (Phase 7)
- Peak concurrent viewer metrics per stream

**What**: A scheduled service in stream-service that harvests presence keys and writes aggregated snapshots to PostgreSQL.

#### Architecture

```
Redis presence keys              HeartbeatHarvestService        PostgreSQL
(TTL 30s)                        (@Scheduled 30s)               stream_viewer_snapshot
    │                                  │                            │
    ├── stream:presence:a:user1 ──→   SCAN + COUNT ──→   UPSERT (a, 14:30, 198234)
    ├── stream:presence:a:user2 ──→                              │
    ├── stream:presence:b:user5 ──→   SCAN + COUNT ──→   UPSERT (b, 14:30, 4521)
    │                                  │                            │
    │                                  │                     Spring Batch (daily)
    │                                  │                       aggregate → hourly (90 days)
    │                                  │                       aggregate → daily (permanent)
    │                                  │                       DELETE minute rows
```

**Design decisions**:
- **Harvest interval = heartbeat TTL (30s)**: A viewer who sends a heartbeat every 15s will always be counted because their key TTL is refreshed before the next harvest. No risk of double-counting — we take `GREATEST(count, new_count)` on UPSERT.
- **Minute-bucket granularity**: One row per stream per minute, regardless of viewer count. For 1,000 active streams: `1000 × 1440 = 1.44M rows/day` — manageable.
- **Daily compaction via Spring Batch** (or a `@Scheduled` job at low-traffic hours): aggregate minute buckets → hourly (90-day retention) → daily (permanent), then DELETE minute rows.
- **UPSERT with GREATEST**: `INSERT ... ON CONFLICT (stream_id, minute_bucket) DO UPDATE SET viewer_count = GREATEST(stream_viewer_snapshot.viewer_count, EXCLUDED.viewer_count)`. Captures the peak within each minute.
- **No per-user data stored**: Only aggregate counts. Privacy-preserving by design.
- **Separate from `ViewCountFlushService`**: The existing view-count system tracks *unique* viewers (dedup via HSETNX, 24h TTL). This tracks *concurrent* viewers (count via SCAN, 30s granularity). Different questions, different data.

**Files**:

| File | Action | Why |
|------|--------|-----|
| `stream-service/.../service/HeartbeatHarvestService.java` | **New** | `@Scheduled(fixedRate=30000)` — SCANs `stream:presence:*`, groups by streamId, batch-UPSERTs to `stream_viewer_snapshot` |
| `stream-service/.../persistence/entity/StreamViewerSnapshotEntity.java` | **New** | Entity mapping for `stream_viewer_snapshot` table |
| `stream-service/.../persistence/repository/StreamViewerSnapshotRepository.java` | **New** | R2DBC repository with custom UPSERT query |
| `stream-service/.../config/HeartbeatHarvestProperties.java` | **New** | `@ConfigurationProperties(prefix = "streaming.heartbeat-harvest")` — harvest interval, retention |
| `stream-service/.../db/migration/V14__create_stream_viewer_snapshot.sql` | **New** | Flyway migration: table + indexes |
| `stream-service/.../service/StreamService.java` | Modify | No changes — harvester uses Redis directly |
| `docs/adr/stream/0010-viewer-heartbeat-analytics-pipeline.md` | **New** | ADR documenting the design |
| `docs/adr/insight/0001-insight-service-architecture.md` | Modify | Add `stream_viewer_snapshot` as upstream data source for analytics dashboards |
| `docs/IMPLEMENTATION-PLAN.md` | Modify | Add A4 to Phase 4 checklist, update Phase 5/6/7 references |

**Schema**:
```sql
CREATE TABLE IF NOT EXISTS stream_viewer_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    stream_id   UUID NOT NULL,
    minute_bucket TIMESTAMPTZ NOT NULL,  -- truncated to minute
    viewer_count BIGINT NOT NULL DEFAULT 0,
    harvested_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_stream_minute UNIQUE (stream_id, minute_bucket)
);

CREATE INDEX ix_snapshot_stream_time
    ON stream_viewer_snapshot (stream_id, minute_bucket DESC);
```

**Harvest query** (R2DBC):
```java
// For each active stream, UPSERT the viewer count at the current minute bucket
String sql = """
    INSERT INTO stream_viewer_snapshot (stream_id, minute_bucket, viewer_count)
    VALUES (:streamId, :bucket, :count)
    ON CONFLICT (stream_id, minute_bucket)
    DO UPDATE SET viewer_count = GREATEST(
        stream_viewer_snapshot.viewer_count,
        EXCLUDED.viewer_count
    )
    """;
```

**ADR**: [stream/0010](adr/stream/0010-viewer-heartbeat-analytics-pipeline.md) — to be written.

**Validate**: Start a stream → 2 viewers open watch page → wait 30s → query `SELECT * FROM stream_viewer_snapshot` shows 1 row with viewer_count >= 2

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Fan-out blocks Kafka listener thread | Medium | Low | `deliverToMany()` uses `flatMap` concurrency 8 on bounded elastic; `blockOptional(10s)` is acceptable for MVP follower counts |
| `StreamEvent` field breaks chat-service deserialization | Low | Medium | Java record with nullable field — JSON unknown field handling ignores it on old consumers |
| Viewer count Redis SCAN impacts performance | Low | Low | Lightweight string key scan; 30s TTL keeps population small; only streams with SSE viewers are scanned |
| Harvest UPSERT contention at scale | Medium | Medium | `GREATEST()` on UPSERT avoids lost-update; minute-bucket granularity limits rows to ~1.4M/day for 1000 active streams |
| 12M heartbeats/min at peak (200k viewers) | — | — | Harvesting aggregates at source — one row per stream per minute regardless of viewer count. Raw heartbeats never touch the database |

---

## Execution Order

```
A3 (dedup, 1 file) ──→ A2.1 (StreamEvent, 3 files) ──→ A2.2 (fan-out, 3 files)
                            │
A1.1 (SSE push, 3 files) ──→ A1.2 (watch UI, 3 files)
                            │
A1.3 (cards, 6 files) ──────┘
                            │
A4 (harvest, 8 files) ──────┘  (independent — can run in parallel with any)
```

A3 + A2.1 are prerequisites. After A2.1, A2.2 and A1.1 can run in parallel. A1.2 depends on A1.1. A1.3 depends on A1.1 (batch viewer count method). A4 is fully independent.

---

## Validation

```bash
# Backend compilation (all services)
cd main/source/backend && ./gradlew compileJava

# Backend tests
cd main/source/backend && ./gradlew :stream-service:test :notification-service:test

# Frontend build
cd main/source/frontend/streaming-ui && npm run build

# Manual E2E — viewer presence:
# 1. Start infra: docker compose up -d
# 2. Start all services
# 3. Browser A: open watch page for live stream
# 4. Browser B: open same watch page
# 5. Both show viewer count "2"
# 6. Close Browser B → count drops to "1" within 30s

# Manual E2E — fan-out:
# 1. User A follows User B (channel page Follow button)
# 2. User B starts a stream (dashboard)
# 3. User A sees SSE notification toast + bell dropdown entry

# Manual E2E — harvest:
# 1. Stream running with 2 viewers
# 2. Wait 30s
# 3. SELECT * FROM stream_viewer_snapshot — shows row with viewer_count >= 2

# Manual E2E — dedup:
# 1. Check logs: dedup key format is dedup:stream.control:notification-consumer:{eventId}
```

---

## Appendix A: Deferred Items

These were discussed and intentionally deferred:

| Item | Deferred To | Notes |
|------|------------|-------|
| SSE connection consolidation (1 per user) | Phase 6.4 | WebSocket unification solves this at the protocol level |
| REST heartbeat → WebSocket migration | Phase 6.4 | `PresenceService` is abstracted for clean migration |
| Heartbeat harvest daily compaction (Spring Batch) | Phase 6.4+ or 7 | Minute-bucket data is manageable without compaction initially |
| `ReactiveNotificationPreferenceRepository` (two-step fan-out) | Post-MVP | All followers get all channels for now |
| Outbox-driven `FanOutJob` + `FanOutPoller` | Post-MVP | Inline fan-out sufficient until subscriber counts warrant |

## Appendix B: Phase 6.4 WebSocket Design Sketch

When Phase 6.4 lands, the current architecture (2 SSE + 1 REST polling + REST heartbeats) collapses into one WebSocket per user:

```
Browser ←── WebSocket ──→ Gateway ──→ Redis Pub/Sub ←── stream-service
  (1 conn)                                          ←── chat-service
                                                    ←── notification-service
```

**Client → Server frames (FE produces):**
```json
{ "type": "chat:send",       "roomKey": "...", "content": "hello" }
{ "type": "heartbeat",       "streamId": "..." }
```

**Server → Client frames (BE produces):**
```json
{ "type": "stream:status",   "streamId": "...", "status": "LIVE" }
{ "type": "chat:message",    "roomKey": "...", "author": "...", "content": "..." }
{ "type": "notification",    "category": "...", "title": "...", "body": "..." }
```

**How each current concern migrates:**

| Current | Phase 6.4 Replacement |
|---------|----------------------|
| `PresenceService` 15s REST heartbeat | WS `heartbeat` frame on same interval; presence TTL refreshed on any frame |
| `StreamSseService` SSE connection | WS `stream:status` frames from Redis Pub/Sub |
| `NotificationService` SSE connection | WS `notification` frames from Redis Pub/Sub |
| Chat 3s REST polling | WS `chat:message` frames from Redis Pub/Sub |

**stream-service presence handling in 6.4:**
- Listen on Redis Pub/Sub channel `presence:heartbeat` for incoming heartbeat frames
- `SETEX stream:presence:{streamId}:{userId} "1" 30` on each heartbeat
- Already implemented — just the transport changes from HTTP POST to WS frame

**Prerequisite for 6.4**: A new `ws-gateway` service or Spring WebFlux WebSocket support in the existing gateway. Redis Pub/Sub as the service mesh backbone.
