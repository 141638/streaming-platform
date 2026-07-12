# Plan: Chat Moderation — Wave 2 (Proactive Moderation Push)

**Status:** Planned — **dependency-gated, not scheduled** (see [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md)) · **Branch:** TBD (off `feat/chat-moderation-ux` once Wave 1 merges) · **Date:** 2026-07-12 · **Updated:** 2026-07-12 — notification cadence / de-spam design added (§ *Notification cadence & de-spam*)

> Wave 1 (moderator UI + reactive ban floor) shipped and is committed. This plan
> is the forward design for Wave 2 — the *proactive* banned-user experience
> (notification bell + in-stream popup + input disabled before the user types).
> **Do not start building until the two prerequisites below hold.** The shipped
> enforcement floor already removes the raw-403 UX, so push is additive.

## Summary

When a moderator bans/unbans a user, chat-service emits a domain event to Kafka.
A real (not scaffold) **notification-service** consumes it, persists a
notification, and pushes it over **SSE** to the affected user's connected
clients. The frontend renders a bell + an in-stream popup and, when the room is
mounted, flips the chat-panel to a disabled state **without** waiting for a send
attempt. Temp-ban *expiry* re-enables client-side (no event); only a **manual
early unban** emits an `UNBANNED` event.

**Architectural spine (from [ADR-0006](../adr/chat/0006-moderation-ux-capability-and-push-split.md)):** the chat send-gate is an **enforcement floor** (the 403 → disable, already shipped) that must remain correct *independently* of this pipeline. Push is a **proactive layer** on top — never a dependency of core chat usability.

## Hard prerequisites (the gate)

Wave 2 **must not** begin until **both** are true:

1. **Kafka broker owned & operated by this team.** The KRaft compose exists at `main/docker/kafka/` (targets `localhost:9094`, matching notification-service's `KAFKA_BOOTSTRAP_SERVERS`), but this team does not yet run a docker-native (or managed) runtime for it the way the stream team does. Trigger: a broker reachable at the configured bootstrap in this team's environment, with topic create/inspect access.
2. **notification-service foundation built.** Today it is a **scaffold**: Kafka *consumer* config + R2DBC/Flyway (`notification` schema: `notification_outbox`, `channel_subscription`) + JWT + Eureka are wired, but the only code is `PingController` and a `StreamControlListener` that just logs. No persist, no REST, no SSE.

A third, smaller dependency (inside Wave 2, not a pre-gate): **chat-service is consume-only today** and must gain a Kafka *producer*.

## Current state (inventory)

| Piece | State | Evidence |
|-------|-------|----------|
| Kafka broker | Compose defined, **not operated by this team** | `main/docker/kafka/single-broker/docker-compose.yaml` (KRaft, `apache/kafka:4.1.2`, ext `:9094`) |
| notification-service | **Scaffold only** | `PingController`, `StreamControlListener` (logs), `V1` schema (outbox + channel_subscription), JWT/R2DBC/Kafka-consumer config |
| chat-service producer | **Missing** (consume-only) | only `messaging/StreamControlListener` (consumer); no `KafkaTemplate`/producer |
| Enforcement floor | **Shipped** (Wave 1) | `chat-panel` maps 403 `CHAT_USER_BANNED` → disable + banner |
| SSE anywhere | **None** | no `ServerSentEvent`/`EventSource` in the codebase |
| Frontend toast/card/bell | ✅ **Prebuilt** (2026-07-12) | `ToastService`, `NotificationService` scaffold, `NotificationCard` molecule, `NotificationToast` host, `NotificationBell` button, `UserProfilePicture` atom, `NotificationDto`. DTO aligned with notification-service plan. Mock-triggerable. See [retro](notification-toast-infrastructure-retrospective.md). |

## Target event flow

```
Moderator bans/unbans (chat-service ModerationService)
      │  (Wave 1: persist chat_ban → guard returns 403 on next send = ENFORCEMENT FLOOR, already shipped)
      └──► publish chat.moderation {type, roomKey, subject, expiresAt, reason, occurredAt, eventId}   (NEW producer)
                 │  Kafka topic: chat.moderation
                 ▼
      notification-service (per-instance consumer, group=notification-service)
                 ├─ persist Notification (idempotent on eventId; notification_outbox drives reliable delivery)
                 ├─ REST:  GET /api/notifications (bell list + unread), POST /{id}/read
                 └─ SSE :  GET /api/notifications/stream  (text/event-stream, fetch-based Bearer — D1)
                              │  push to this user's locally-connected clients
                              ▼
      Frontend NotificationService ─► bell + in-stream popup
                              └─► signals chat-panel (if mounted on that room) → PROACTIVE disable
```

## Event contract (`chat.moderation`)

```jsonc
{
  "eventId":   "uuid",              // idempotency key for consumer dedup
  "type":      "BANNED | UNBANNED",
  "roomKey":   "string",
  "subject":   "string (banned user's JWT sub)",
  "bannedBy":  "string (moderator sub)",
  "reason":    "string | null",
  "expiresAt": "ISO-8601 | null",   // null = permanent
  "occurredAt":"ISO-8601"
}
```
- **Keying:** partition by `subject` so a user's events stay ordered.
- **Delivery:** at-least-once; consumer dedups on `eventId` (**D2**). A chat-side transactional outbox is **condition-deferred** — add only if at-least-once proves insufficient.
- **Only manual early unban emits `UNBANNED`.** Temp-ban expiry is lazy (server `BanSendGuard.isActive` + client `now`-tick) — no event.
- **Duration change** (Wave-1.1 `ModerationService.updateBanDuration`, `PATCH …/bans/{subject}`) emits a `BANNED` event carrying the new `expiresAt` (an idempotent re-assert) so the client re-bases its countdown; a shortened time or a lift still resolves on the client `now`-tick. A `// Wave 2 (ADR-0007)` emit-marker already sits in `updateBanDuration` — wire the producer there in P2.1. Repeated edits are kept from spamming the banned user by the three-layer design in **Notification cadence & de-spam** below.

## Notification cadence & de-spam

A moderator editing a ban's duration is one *logical* action but can produce several
`BANNED` re-asserts (ratcheting 1h→24h→7d→permanent). Left unmanaged that is a burst
of bell-rings + toasts to the banned user. **`eventId` dedup (D2) does not solve
this** — it suppresses *re-delivery of one event*, not *N distinct rapid events*.
Three independent layers keep the banned-user experience calm; each is defense in
depth and none relies on the others:

1. **Source — commit-once moderator UX (SHIPPED, Wave 1.x, `32d0ff1`).** The inline
   duration ladder now *stages* rung changes locally and emits a single
   `updateDuration` on apply, so `1h → permanent` is one PATCH → one event, not
   three. This already collapses the common burst before it reaches Kafka.
2. **Pipeline — latest-wins coalescing (notification-service, P2.2).** Don't trust
   the client alone. Collapse `BANNED` re-asserts for the same `(roomKey, subject)`
   arriving within a short window (≈2–5s), keeping the latest `expiresAt`. The
   scaffolded `notification_outbox` is the home: *supersede* a pending un-delivered
   outbox row for that key rather than appending. Distinct from `eventId` dedup (D2 =
   redelivery protection; this = rapid-event coalescing). `subject`-partitioning
   (already chosen) gives the ordering that makes "keep the latest" well-defined.
3. **Presentation — semantic tiering (frontend, P2.5).** SSE does two jobs with very
   different urgency; split them:
   - **Lock/unlock** is idempotent state reconciliation driven by the absolute
     `expiresAt`/`type` — replaying N events converges. Only `UNBANNED` unlocks;
     `BANNED` (incl. a *tighter* duration) stays locked and just refreshes the
     countdown.
   - **Interruptive toast + bell** is reserved for transitions the user benefits
     from being interrupted for:

     | Transition | Lock | Bell | Interruptive toast |
     |-----------|------|------|--------------------|
     | Initial ban (mid-session) | lock | ✓ | ✓ (reason, until Y) |
     | Duration **increase** / re-assert | stay locked | — | **✗** (silent countdown update) |
     | Duration **reduction / soon-lift** | stay locked | ✓ | optional gentle ("ban shortened") |
     | **Unban** | **unlock** | ✓ | ✓ (the primary one) |

   Rationale: an *increase* doesn't change what the locked user can do; a
   reduction/lift is favorable news they can't otherwise see (the roster is
   `moderate`-gated → 403 for them). So push carries reductions, suppresses increases
   — the only spammy direction (ratcheting up) then produces **zero** toasts even if
   layers 1–2 are bypassed.

**Where direction is computed:** keep the chat-service event dumb (absolute
`expiresAt` only). notification-service derives increase / decrease / lift by
comparing to the last persisted notification for that `(room, subject)`, so
chat-service stays ignorant of UX policy.

## Phased build (only after the gate opens)

| Phase | Scope | Exit |
|-------|-------|------|
| **P2.0** Infra readiness | Stand up / verify the team's Kafka broker; create `chat.moderation`; verify notification-service connects | broker reachable, topic exists |
| **P2.1** chat-service producer | Add reactive Kafka producer; emit `chat.moderation` on ban/unban after commit; tests | events on the topic (kafka-ui) |
| **P2.2** notification foundation | ADR-0000 for notification-service; consume `chat.moderation` → persist Notification + outbox row (idempotent on `eventId`) | rows created; consumer idempotent |
| **P2.3** notification REST | `GET /api/notifications` (list + unread count), `POST /{id}/read`; JWT-scoped to caller `sub` | bell can list/mark-read |
| **P2.4** SSE delivery | `GET /api/notifications/stream` (`text/event-stream`); per-instance consume + in-memory connection registry keyed by `sub`; gateway: disable buffering, long read timeout | a connected client receives a live event |
| **P2.5** frontend | `NotificationService` (fetch-based Bearer SSE, **D1**), bell + unread, in-stream popup; chat-panel consumes the ban/unban signal → proactive disable (floor stays as backstop) | ban issued elsewhere disables the victim live |
| **P2.6** verify + docs | e2e across services; notification-service ADR(s); update this plan → retrospective | green; ADRs merged |

## Cross-service decisions

| # | Decision | Choice |
|---|----------|--------|
| D1 | SSE auth | **fetch-based Bearer** (`@microsoft/fetch-event-source`) — reuses interceptor/refresh; native `EventSource` can't send `Authorization` |
| D2 | Delivery guarantee | at-least-once + consumer dedup on `eventId`; chat-side outbox condition-deferred |
| D3 | Chat-panel signal source | consume the notification SSE as a *proactive hint*; keep the 403 floor as enforcement (no second chat-service SSE) |
| D4 | SSE horizontal fanout | each notification-service instance consumes the topic + pushes to locally-connected users; multi-instance connection registry **deferred until >1 instance** |
| D5 | Scope | notification-service is a **general platform hub** (outbox + channel_subscription already model multi-channel); moderation is its *first client*, not its only shape |
| D6 | Duration-change notification cadence | commit-once UX (**shipped**, `32d0ff1`) + latest-wins coalescing by `(room,subject)` (P2.2) + semantic tiering — increases silent, unban/reduction toast (P2.5). See *Notification cadence & de-spam* |

## Risks

| Risk | Mitigation |
|------|-----------|
| Kafka infra ownership slips → whole wave blocked | The gate is explicit; Wave-1 floor is a complete baseline; nothing urgent depends on push |
| Building a moderation-only push instead of the hub | D5 + [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md) mandate the general hub |
| SSE auth friction (localStorage token, not cookie) | D1 fetch-based Bearer decided up front |
| Duplicate/again-delivered events | D2 idempotency on `eventId` |
| Gateway buffering SSE | P2.4 explicitly configures buffering off + long timeout |
| **Global gateway `response-timeout` (10s) severs SSE** — added in Wave-1.1 (`gateway-service` httpclient) to bound slow routes | P2.4 must **exclude** `/api/notifications/stream` from the global timeout: give the SSE route its own `response-timeout: -1` (or a per-route override), not the 10s default. Flagged in the Wave-1.1 retrospective (§3 G2). |
| Rapid ban-duration edits spam the banned user with bell-rings/toasts | Three-layer de-spam (see *Notification cadence & de-spam*): commit-once UX (**shipped**), server-side latest-wins coalescing, and semantic tiering that makes duration *increases* non-interruptive. `eventId` dedup does **not** cover this. |

## Non-goals / condition-deferred

- **Expired-ban reaper** (dormant `chat_ban` rows) — [ADR-0006]/[ADR-0005] posture; only if cardinality warrants.
- **Multi-channel delivery** (email/web-push) — `channel_subscription` supports it, but out of scope until a real second channel exists.
- **Rich in-stream ban detail before Wave 2** — the Wave-1 banner stays generic (403 carries no expiry/reason).

## References
- [ADR-0006](../adr/chat/0006-moderation-ux-capability-and-push-split.md) — moderation UX; floor/push split
- [ADR-0007](../adr/chat/0007-proactive-push-infrastructure-gated.md) — this wave's dependency gate
- [ADR-0005](../adr/chat/0005-moderation-domain-condition-triggered.md) — condition-triggered posture (the mold this reuses)
- Scaffold: `notification-service` (`PingController`, `V1` outbox + channel_subscription, `StreamControlListener`)
- Infra: `main/docker/kafka/single-broker/docker-compose.yaml`
