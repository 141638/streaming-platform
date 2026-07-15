# ADR-0000: Architecture Foundation — Notification Service

**Date**: 2026-07-14
**Status**: accepted
**Deciders**: hieuht, Claude

> **Entry point.** Read this before modifying any code in `notification-service/`.
> It covers the architectural style decision, package structure, entity patterns,
> and how each third-party service is integrated. The service is the platform's
> notification hub — moderation push is its first client, not its only shape.

## Context

The notification service is a control-plane component that consumes domain events
from Kafka, persists notifications, and delivers them to users via REST (bell list)
and SSE (real-time push). It was scaffolded during Phase 3 (chat) as a consumer
shell — Kafka config, R2DBC/Flyway, JWT auth, Eureka — with a `StreamControlListener`
that only logged. As of 2026-07-14, the Kafka consumer infrastructure is
production-ready (Redis SETNX dedup, DLQ with 3-retry backoff, routing for all 5
stream event types), but the handlers are still stubs. The service now needs its
foundation: domain model, persistence, REST API, and SSE delivery.

The service is designed as a **general platform notification hub**. Its first
concrete client is chat moderation push (Wave 2 —
[ADR-0007](../chat/0007-proactive-push-infrastructure-gated.md)), but its schema
(`notification_outbox` for reliable delivery, `channel_subscription` for
multi-channel preferences) models multi-category, multi-channel delivery from the
start. Stream lifecycle notifications (Phase 5.2) and @mention notifications are
natural next consumers.

Key constraints:
- Spring Boot 3.3.6, WebFlux (reactive), Java 21
- R2DBC for PostgreSQL, Reactive Redis for dedup cache
- Kafka consumer (existing) + eventual producer (for `notification_outbox` → downstream channels like email)
- JWT authentication with `@AuthenticationPrincipal Jwt jwt`
- Must interoperate with gateway (route + SSE timeout exclusion), auth-service (JWT validation), chat-service (moderation events), and stream-service (lifecycle events)

## Decision

We use **Layered Reactive** architecture with the package structure `api/` →
`application/` → `domain/` → `infrastructure/`, matching the stream-service and
chat-service conventions documented in
[`docs/SERVICE-ARCHITECTURE.md`](../../SERVICE-ARCHITECTURE.md). Domain entities
use the `Persistable<UUID>` + `@Transient isNew` pattern. DTOs are Java records.
All dependencies are injected via constructor injection with Lombok
`@RequiredArgsConstructor`.

The service has **two inbound channels** (Kafka consumers) and **two outbound
channels** (REST + SSE), making it the first service in the platform with both
push and pull delivery for the same resource:

```
Kafka (stream.control) ──► StreamControlListener ──► NotificationService ──► PostgreSQL
Kafka (chat.moderation) ─► ModerationListener      (persist + outbox)    ├─► REST (GET /v1/notifications)
                         (Wave 2, not yet built)                         ├─► SSE  (GET /v1/notifications/stream)
                                                                         └─► Kafka producer (future: email channel)
```

## Alternatives Considered

### Alternative 1: Single merged consumer
- **Pros**: One `@KafkaListener`, simpler config, shared dedup logic.
- **Cons**: Couples stream lifecycle and chat moderation in one class. Different topics have different processing needs (stream events → broadcast notifications; moderation events → targeted push). Separate listeners keep the routing surface clean.
- **Why not**: The `StreamControlListener` already exists and is self-contained. Adding moderation to it would grow the switch statement and mix concerns. Separate listeners per topic is the platform convention (chat-service's `StreamControlListener` is also topic-specific).

### Alternative 2: SSE-only (no REST polling)
- **Pros**: Simpler API surface — one endpoint, real-time only.
- **Cons**: No bell history. On page load, the user sees zero notifications until the next event arrives. REST provides the "catch-up" list and unread count that makes the bell meaningful on first render.
- **Why not**: The frontend toast infrastructure (5.0) already assumes a fetch-then-stream model — `GET /v1/notifications` for initial list, then SSE for live updates. REST + SSE is the standard pattern (Slack, GitHub, Discord).

### Alternative 3: WebSocket instead of SSE
- **Pros**: Bidirectional, single connection, well-supported in Spring.
- **Cons**: Heavier protocol for a unidirectional push use case. SSE is simpler (HTTP, auto-reconnect, no STOMP layer). Chat-service already plans a WebSocket upgrade (Phase 6.4) for bidirectional messaging — notification push doesn't need that complexity.
- **Why not**: SSE is the right tool for server→client event streams. Fetch-based SSE (`@microsoft/fetch-event-source`) reuses the existing auth interceptor/refresh pipeline. If the platform later consolidates on WebSocket for everything, migration is straightforward (SSE → WebSocket is a transport swap, not an architecture change).

### Alternative 4: Anemic scaffold (keep the stubs, grow organically)
- **Pros**: No upfront ceremony. Add endpoints as needed.
- **Cons**: The scaffold is already 7 files with zero business logic. Growing organically without an architectural decision means each addition re-litigates package placement, entity patterns, and naming. The result is inconsistency that costs more to fix later.
- **Why not**: Already rejected by the platform convention. Every service (auth, stream, chat) follows layered reactive. The notification service is the fourth service — consistency matters more now, not less.

## Consequences

### Positive
- **Consistency**: Same package layout, entity patterns, and naming as stream/chat/auth services — a developer can read any codebase without re-learning conventions.
- **Testability**: Each layer can be unit-tested in isolation. `NotificationService` can be tested with mock repositories; `NotificationController` with `@WebFluxTest`.
- **Hub architecture**: The `notification_outbox` pattern (modeled in V1) supports reliable multi-channel delivery — email, push, in-app — without restructuring when a second channel is added.
- **SSE reuse**: The `SseConnectionRegistry` (per-user sink map) is category-agnostic. Adding `CHAT_MENTION` notifications reuses the same delivery pipe.

### Negative
- **Not pure**: The "domain" entities carry Spring Data annotations (`@Table`, `@Id`), so they're not framework-free. This is pragmatic — the platform uses Spring Data R2DBC exclusively.
- **Two consumers, two DTO shapes**: `stream.control` events carry `StreamEvent` (from common); `chat.moderation` events will carry a different envelope. The service must map both into a unified `Notification` entity. This is a deliberate design choice — the entity is the unification point.

### Risks
- **SSE connection storm on restart**: If the service restarts, all clients reconnect simultaneously. **Mitigation**: SSE auto-reconnect with jitter (built into `@microsoft/fetch-event-source`). Gateway connection pooling handles the HTTP side.
- **Outbox table growth**: The `notification_outbox` table (V1) is designed for reliable delivery to downstream channels (email, push). If no downstream channels exist yet, it accumulates rows. **Mitigation**: the outbox poller only runs when a channel producer is active; initial Phase 5 foundation (REST + SSE) doesn't use the outbox.
- **`blockOptional()` in Kafka listener**: `StreamControlListener.onStreamControl()` uses `.blockOptional(Duration.ofSeconds(10))` to bridge reactive Redis dedup with the blocking `@KafkaListener`. This is pragmatic for now; if consumer throughput becomes a bottleneck, switch to `ReactiveKafkaConsumerTemplate`. Accepted for the foundation phase.

## Third-Party Integration Catalog

How each external dependency is integrated into this service.

### PostgreSQL

| Aspect | Detail |
|--------|--------|
| **Role** | System of record — all notifications and channel subscriptions are durable here |
| **Schema** | `notification` — owned exclusively by this service |
| **Driver** | R2DBC (`r2dbc-postgresql`) — reactive, non-blocking |
| **Migrations** | Flyway via JDBC side-channel (`spring.flyway.url`) |
| **Repositories** | `ReactiveCrudRepository` with derived query methods |
| **Entity pattern** | `Persistable<UUID>` + `@Transient isNew` (UUID assigned pre-persist) |
| **Existing tables** | `notification_outbox` (V1 — reliable delivery to downstream channels), `channel_subscription` (V1 — user preferences per channel) |
| **Planned tables** | `notification` (V2 — the core entity: recipient, category, action, title, body, metadata, isRead) |

### Redis

| Aspect | Detail |
|--------|--------|
| **Role** | Deduplication cache for Kafka consumer idempotency |
| **Pattern** | `SETNX` with 24h TTL — defense-in-depth against at-least-once duplicates. Key format: `dedup:{topic}:{consumerGroupId}:{eventId}` per [ADR common/0003](../common/0003-cross-service-event-dedup-key-scoping.md). Current implementation uses unscoped key (`dedup:stream-event:{eventId}`) — safe until a second service adds Redis SETNX for the same topic; refactoring tracked in the same ADR. |
| **Resilience** | `.onErrorResume()` fallback — if Redis is unreachable, process the event anyway (duplicate is better than dropped) |
| **Library** | `ReactiveRedisTemplate<String, String>` |
| **Future use** | SSE connection registry (in-memory `ConcurrentHashMap` for single-instance; Redis Pub/Sub for multi-instance fanout per ADR-0007 D4) |

### Kafka

| Aspect | Detail |
|--------|--------|
| **Role** | Consumer of `stream.control` + future `chat.moderation` topics — inbound event backbone |
| **Consumer** | `StreamControlListener` (`@KafkaListener`, group = `notification-service`) |
| **Dedup** | Redis `SETNX` on `eventId` (24h TTL) per-event, before routing |
| **DLQ** | `CommonErrorHandler` bean — 3 retries with 1s fixed backoff → `DeadLetterPublishingRecoverer` → `stream.control.dlq` |
| **Serialization** | JSON via Jackson `ObjectMapper` — `StreamEvent` from `com.streaming.common.messaging` |
| **Producer** | `KafkaTemplate<String, String>` scaffolded for DLQ (`dlqPublisher` bean). Future: notification outbox poller will produce to email/push channels |
| **Topics** | `stream.control` (consuming), `stream.control.dlq` (dead-letter). Planned: `chat.moderation` (consuming, Wave 2) |

### Service Registry (Eureka)

| Aspect | Detail |
|--------|--------|
| **Role** | Client-side service discovery — registers with Eureka, resolved by gateway via `lb://notification-service` |
| **Library** | `spring-cloud-starter-netflix-eureka-client` |

## Forward-Looking Sketch

What's planned but not yet built. See [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) for the
authoritative phase checklist.

| Item | Phase | Description |
|------|-------|-------------|
| Notification domain + persistence | 5.1 | ✅ **Done (2026-07-15)** — `Notification` entity, `ReactiveNotificationRepository`, V2 migration, `NotificationCategory` enum with R2DBC converters |
| REST API | 5.1 | ✅ **Done (2026-07-15)** — `GET /v1/notifications` (cursor-paginated, JWT-scoped), `GET /v1/notifications/unread-count`, `POST /v1/notifications/{id}/read` |
| Consume → persist wiring | 5.2 | ✅ **Done (2026-07-15)** — `StreamControlListener` wired to `NotificationService.createFromStreamEvent()`; STREAM_STARTED/STREAM_ENDED persist, others are debug no-ops |
| Subscription management | 5.1b | `channel_subscription` CRUD, `NotificationDispatcher` interface, outbox management — deferred to next session |
| SSE delivery | 5.2b | ✅ **Done (2026-07-15)** — `GET /v1/notifications/stream` (`text/event-stream`, JWT-scoped, 30s heartbeat), `SseConnectionRegistry` (in-memory, multi-tab per user via `CopyOnWriteArraySet<Sinks.Many>`), `NotificationService` wired to push via SSE, gateway per-route `response-timeout: -1`, frontend `@microsoft/fetch-event-source` connection + bell unread badge |
| Chat moderation consumer | Wave 2 | `ModerationListener` for `chat.moderation` topic — BANNED/UNBANNED → persist → SSE push |
| Email adapter | 5.3 | SMTP integration via Spring Mail, templated emails, outbox-driven dispatch |
| Follower fan-out | 5.2b | Subscription lookup on stream events → notify all followers (currently only notifies the broadcaster) |
| Multi-instance SSE fanout | 6.x | Redis Pub/Sub for cross-instance connection registry when service scales past 1 instance |

## References

- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural style recommendations
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — authorization model and JWT claims design
- [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) — master phase plan; Phase 5 checklist
- [ADR-chat-0007](../chat/0007-proactive-push-infrastructure-gated.md) — Wave 2 dependency gate (the notification foundation is a hard prerequisite)
- [chat-moderation-wave2-proactive-push.md](../../plans/chat-moderation-wave2-proactive-push.md) — the gated build plan that depends on this service
- [notification-toast-infrastructure-retrospective.md](../../plans/notification-toast-infrastructure-retrospective.md) — frontend toast/card/bell prebuild (5.0)
- `notification-service` scaffold: `PingController`, `V1__bootstrap_notification_schema.sql`, `StreamControlListener`, `KafkaConsumerConfig`
