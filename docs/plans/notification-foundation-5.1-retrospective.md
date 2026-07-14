# Notification Foundation (5.1 + 5.2 SSE) — Implementation Retrospective

**Date:** 2026-07-15
**Status:** Complete — notification core + SSE delivery shipped; subscription CRUD + follower fan-out + outbox management deferred to next session

## 1. What was implemented (vs the original plan)

| Planned item | Files | Notes |
|-------------|-------|-------|
| Notification domain model | `domain/Notification.java`, `domain/NotificationCategory.java` | `Persistable<UUID>` entity following `ChatMessage` pattern; enum with `wireValue()`/`fromWireValue()` matching `MessageType` convention |
| R2DBC enum converters | `config/CategoryToStringConverter.java`, `config/StringToCategoryConverter.java`, `config/R2dbcConfig.java` | `@WritingConverter`/`@ReadingConverter` + `R2dbcCustomConversions.of(PostgresDialect.INSTANCE, ...)` — mirrors chat-service pattern |
| V2 migration | `db/migration/V2__create_notification_table.sql` | `notification.notification` table: id, recipient_subject, category, action, title, body, metadata (JSONB), is_read, created_at; 2 indexes (cursor pagination, unread partial) |
| Repository | `infrastructure/persistence/ReactiveNotificationRepository.java` | `ReactiveCrudRepository` — cursor pagination, unread count (partial index), ownership-scoped `findByIdAndRecipientSubject` |
| NotificationService | `application/NotificationService.java` | `createFromStreamEvent()` maps 5 event types → persist STREAM_LIVE/STREAM_ENDED for broadcaster, no-op for others; `getNotifications(cursor, limit)`; `getUnreadCount()`; `markAsRead(id, recipient)` with ownership enforcement |
| REST API | `api/NotificationController.java`, `api/dto/NotificationResponse.java`, `api/dto/UnreadCountResponse.java` | `GET /v1/notifications?cursor=&limit=`, `GET /v1/notifications/unread-count`, `POST /v1/notifications/{id}/read` — all scoped to `jwt.getSubject()` |
| Error handling | `api/error/NotificationApiError.java`, `api/error/NotificationExceptionHandler.java` | `NotificationNotFoundException` → 404 with `NOTIFICATION_NOT_FOUND` code; mirrors `ChatApiError` + `ChatExceptionHandler` |
| Kafka consumer wiring | `messaging/StreamControlListener.java` (modified) | Injected `NotificationService`; replaced 5 stub handlers with `createFromStreamEvent()` calls; kept `STREAM_CREATED`/`SCHEDULED`/`CANCELLED` as debug-level no-ops |
| Lombok dependency | `build.gradle.kts` (modified) | Added `compileOnly("org.projectlombok:lombok")` + `annotationProcessor` — was missing from the scaffold |

### 5.2 SSE Delivery (2026-07-15 session)

| Planned item | Files | Notes |
|-------------|-------|-------|
| SseConnectionRegistry | `infrastructure/SseConnectionRegistry.java` (new) | `ConcurrentHashMap<String, CopyOnWriteArraySet<Sinks.Many<NotificationResponse>>>` — multi-tab per user, `onBackpressureBuffer(64)`, `tryEmitNext` fire-and-forget push, auto-cleanup on sink termination |
| NotificationSseController | `api/NotificationSseController.java` (new) | `GET /v1/notifications/stream` returning `Flux<ServerSentEvent<NotificationResponse>>`; per-user sink via `registry.register(subject)`; 30s heartbeat via `Flux.interval()` merged with notification flux; JWT-scoped |
| Service → SSE push wiring | `application/NotificationService.java` (modified) | Injected `SseConnectionRegistry`; `NotificationResponse.from(saved)` pushed after each persist in `STREAM_STARTED`/`STREAM_ENDED` cases (fire-and-forget in `doOnSuccess` block) |
| @JsonProperty DTO contract | `api/dto/NotificationResponse.java` (modified) | Added `@JsonProperty("read")` on `isRead` component — ensures explicit JSON field name regardless of Jackson bean naming |
| Gateway SSE timeout | `gateway-service/application.yml` (modified) | `notification-service-sse` route before general notification route with per-route `response-timeout: -1` + `connect-timeout: 5000` |
| Frontend DTO reconciliation | `contracts/notification.dto.ts` (rewritten) | Aligned to backend `NotificationResponseDto`: `category` → `BackendNotificationCategory`, `body`→`message`, `read` field, `clickAction` separate from `action` key; added `mapNotificationResponse()`, `severityFromCategory()` |
| Frontend REST + SSE wiring | `services/notification.service.ts` (rewritten) | `getNotifications(cursor, limit)`, `refreshUnreadCount()`, `markAsRead(id)` via HttpClient; `connect()`/`disconnect()` via `@microsoft/fetch-event-source` with Bearer token from `AuthService`; `BehaviorSubject<number>` for unread badge |
| Card/timestamp fix | `notification-card.component.ts` (modified) | `timestamp` → `createdAt`; `action.type/.route` → `clickAction.type/.route` |
| Toast navigation fix | `notification-toast.component.ts` (modified) | `action?.route` → `clickAction?.route` |
| Bell unread badge | `notification-bell.component.ts` + `.html` (rewritten) | `BadgeModule` with `toSignal(unreadCount$)`; "99+" cap; severity="danger" |
| SSE lifecycle | `app.component.ts` (modified) | `effect()` watching `isAuthenticated()` → `connect()` on login, `disconnect()` on logout; `ngOnDestroy` cleanup |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|-----------|-------------|--------|
| `channel_subscription` CRUD | Next session (5.1b) | IMPLEMENTATION-PLAN.md §5.1 | Table exists (V1), but no entity/repository/service — gated on subscription domain model |
| `NotificationDispatcher` interface | Next session (5.1b) | IMPLEMENTATION-PLAN.md §5.1 | Dispatcher is the hub pattern seam — needs the subscription model |
| Follower fan-out | Next session (5.2b) | IMPLEMENTATION-PLAN.md §5.2 | Currently only notifies the broadcaster; needs subscription model |
| Outbox management (poller/producer) | 5.3 (email adapter) | IMPLEMENTATION-PLAN.md §5.3 | No downstream channels yet; no poller runs until a channel producer is active |
| Redis Pub/Sub multi-instance SSE | Phase 6.x | ADR-0000 §Forward-Looking Sketch | Single-instance deployment is sufficient for foundation; Redis fan-out added when 2+ instances needed |
| Chat moderation consumer | Wave 2 | chat-moderation-wave2-proactive-push.md | Gated on Kafka broker ownership + SSE availability (now both met effectively) |
| Email adapter | 5.3 | IMPLEMENTATION-PLAN.md §5.3 | Lower priority than subscription/fan-out |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All deferred items are explicitly tracked.

## 4. Architectural decisions made during implementation

1. **Single-recipient notification model (not broadcast)** — Each `Notification` row targets exactly one `recipientSubject`. Fan-out to followers (multiple recipients per event) is done at the service layer via subscription lookup, not at the entity level. This matches the ADR-0000 hub pattern: the entity is the persistence unit; fan-out is application logic. **Recommendation:** Note in the retrospective — it's a design choice consistent with ADR-0000, not a new ADR.

2. **STREAM_CREATED/SCHEDULED/CANCELLED as no-op notifications** — These event types are routed through `NotificationService.createFromStreamEvent()` but log at debug level and return `Mono.empty()`. The routing is wired so the switch statement is exhaustive, but only STREAM_STARTED/STREAM_ENDED produce persisted notifications. This is deliberate: notification scope expands with subscription/follower features in 5.2. **Recommendation:** Documented in code (log messages + method javadoc) — no ADR needed.

3. **Ownership enforcement at the query level** — `markAsRead` uses `findByIdAndRecipientSubject(id, recipient)` rather than `findById(id)` + manual check. This prevents an attacker from enumerating notification IDs. Same pattern as chat-service's `findByRoomIdAndBannedSubject`. **Recommendation:** Pattern doc candidate — "ownership-scoped repository queries" — but it's already established in stream/chat; document in the retrospective only.

4. **Lombok `isRead` → `setRead()` naming quirk** — For a boolean field named `isRead`, Lombok follows the JavaBeans spec: getter is `isRead()`, setter is `setRead()` (not `setIsRead()`). The factory method must use `setRead(false)`. **Recommendation:** Note in the retrospective — a common gotcha, not ADR-worthy.

### SSE Delivery Decisions (2026-07-15)

5. **Multi-connection per user (`CopyOnWriteArraySet<Sinks.Many>`)** — Each user can hold multiple SSE connections (browser tabs, devices). The registry stores a set of sinks per user subject, not a single sink. Connection removal is atomic: remove the sink, and if the set is empty, remove the map entry. `CopyOnWriteArraySet` chosen for read-heavy iteration (push fans out to all sinks) with infrequent writes (connect/disconnect). **Recommendation:** Note in the retrospective — this is infrastructure detail, not ADR-worthy.

6. **`@JsonProperty("read")` for explicit JSON contract** — Jackson serializes boolean record component `isRead` inconsistently across versions (some strip the `is` prefix, some don't). Adding `@JsonProperty("read")` removes ambiguity and ensures the frontend contract is explicit. **Recommendation:** Pattern doc candidate — "explicit JSON property names for boolean fields on Java records."

7. **In-memory SSE registry (not Redis Pub/Sub)** — The single-instance `ConcurrentHashMap` approach is sufficient for the foundation. Redis Pub/Sub fan-out (Phase 6.x) adds cross-instance push but brings operational complexity (Redis becomes a runtime dependency for push delivery). The upgrade path is clean: a `RedisMessageListener` calls the same `registry.push()` method. **Recommendation:** This is an explicit deferral matching ADR-0000's forward-looking sketch.

8. **`@microsoft/fetch-event-source` over native `EventSource`** — Native `EventSource` doesn't support custom headers (Bearer token). `fetch-event-source` provides Bearer auth, auto-reconnect with jitter, and `onopen` callback for error handling. The frontend already had this dependency plan documented in the toast infrastructure retro. **Recommendation:** Note only — this was the plan all along.

9. **`effect()` for SSE lifecycle in Angular** — An `effect()` in `AppComponent` watches `isAuthenticated()` signal: `true` → `connect()`, `false` → `disconnect()`. This avoids manual lifecycle wiring across components. The SSE connection is managed at the root component level (always mounted), so it survives route changes without reconnection. **Recommendation:** Pattern note — "root-level effect() for connection lifecycle management."

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `IMPLEMENTATION-PLAN.md` §Phase 5 | 5.1 unchecked, 5.2 unchecked | 5.1 notification core (domain + persistence + REST + consumer wiring) is done; 5.2 StreamControlListener wiring is done | Check off 5.1 partial items + 5.2 wiring item; update "Last updated" date |
| `docs/adr/notification/0000-architecture-foundation.md` | Forward-looking sketch lists Notification domain + persistence (5.1), REST API (5.1), Consume → persist wiring (5.2) as planned | All three are now implemented | Add implementation date + commit reference to each item |

## 6. Updated execution order (actual vs planned)

| Planned | Actual | Status |
|---------|--------|--------|
| 5.1: subscription CRUD + outbox + dispatcher | 2026-07-15: notification core (domain + persistence + REST) only | ⚡ Partial — core shipped |
| 5.2: Kafka → dispatch wiring | 2026-07-15: StreamControlListener wired to NotificationService | ✅ Done (pulled forward into 5.1) |
| 5.1: subscription CRUD + outbox + dispatcher | Next session | Remaining |

The scope was narrower than the plan described — we built the notification entity, persistence, REST API, and consumer wiring but left the subscription/outbox/dispatcher pieces for the next session. This split makes sense: the notification core is independently testable and provides immediate value (bell list + unread count), while subscription/outbox are integration points with other subsystems.

## 7. Key risks carried forward

1. **`channel_subscription` schema mismatch risk** — The V1 table exists but the entity/CRUD hasn't been built yet. When we design the subscription model, the schema may need adjustment. **Mitigation:** The table is simple (subscriber_subject, channel, topic_glob, active) — migration overhead is low if changes are needed.

2. **`notification_outbox` idle growth** — The outbox table exists (V1) but no poller trims it. OK for now since no producer writes to it, but if a future feature starts writing to it before the poller is built, rows will accumulate. **Mitigation:** Don't write to outbox until the poller is active; documented in ADR-0000 §Risks.

3. **No Docker host for integration tests** — The notification-service has no Testcontainers tests. Flyway migration V2 runs at startup; the entity mapping (R2DBC converters for NotificationCategory) is untested against a real PostgreSQL. **Mitigation:** Compile-only gate (`./gradlew :notification-service:compileJava`); Docker-gated tests deferred per test-env-deferral-policy.

4. **SSE gateway timeout** — ~~The gateway's global 10s `response-timeout` kills long-lived SSE connections.~~ **Mitigated:** Added `notification-service-sse` route with per-route `response-timeout: -1` placed before the general notification route.

5. **SSE connection storm on restart** — If the service restarts, all clients reconnect simultaneously via `fetch-event-source` auto-reconnect with jitter (built-in). The gateway connection pool handles the HTTP side. No custom jitter logic needed on the server.

---

*Session focus: SSE delivery + frontend wiring. 13 files changed — 2 created, 11 modified. Next: subscription CRUD + follower fan-out.*
