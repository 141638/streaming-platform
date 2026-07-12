# ADR-0006: Chat Moderation UX — Client Capability Signal, Temp-Bans, and the Enforcement-Floor / Proactive-Push Split

**Date**: 2026-07-12
**Status**: accepted (Wave 1); Wave 2 push section is **proposed / condition-deferred**
**Deciders**: hieuht, Claude

## Context

Phase 3.4 shipped the ban/moderation REST API and two-layer authorization ([ADR-0004](0004-two-layer-chat-authorization.md)) but no UI. This work ("Wave 1") adds the moderator surface — embedded in the existing `chat-panel` so every chat consumer (streamer view, moderator view) inherits it with no call-site change — and the banned-user experience. Three questions had to be answered:

1. **Capability discovery**: how does the client know it may moderate *this* room, without re-implementing the PBAC `ent` grammar and without being handed the room owner's identity? Complications: PBAC ships **dark** (`chat.pbac.enabled=false`), and `RoomResponse` deliberately does not expose `broadcasterSubject`, so the `self` scope (a streamer moderating their own room) cannot be evaluated client-side.
2. **Temp-bans**: the `chat_ban` schema has `expires_at` (V3) but `ModerationService.ban` hard-coded `null` (all bans permanent). How to wire a duration and refresh expiry on both ends?
3. **Banned-user experience**: "type a message → get a raw `403` → infer you're banned" is poor UX. How does a banned user learn their state, and how far do we build now vs. later?

## Decision

### 1. Server-computed capability signal on `RoomResponse`
`RoomResponse` carries `viewerCanModerate: boolean`, computed in `ChatService.getRoom` via a new `ChatAuthorization.hasCapability(jwt, RequiredAuthority)` against `chat:moderation moderate` for `room.broadcasterSubject`. `hasCapability` uses the same `EntitlementMatcher` as enforcement but is **independent of `chat.pbac.enabled`** — the flag gates *enforcement* (`requireAccess`), not *capability display*. A `null` principal yields `false`.

The client gates every moderator affordance on this boolean. It never parses the `ent` grammar and never receives `broadcasterSubject`. The signal rides the `GET /rooms/{roomKey}` call the panel already makes on init — zero extra round-trip.

### 2. Temp-bans, lazily expired (no scheduler)
`BanRequest.durationSeconds` (nullable = permanent, `@Positive`) → `expiresAt = now + durationSeconds` in `ModerationService.ban`. Expiry is lazy on both ends and needs no background job: the server `BanSendGuard.isActive` re-checks per send, `listBans` returns **active-only** (`findActiveByRoomId`), and the client filters `activeBans` on a wall-clock tick. A reaper for dormant expired rows is **condition-deferred** (per [ADR-0005](0005-moderation-domain-condition-triggered.md)'s posture) — built only if ban cardinality/query cost warrants.

### 3. Two-layer banned-user experience
The chat send-gate is an **enforcement floor**: `403 CHAT_USER_BANNED` → `chat-panel` disables the input and shows a banner (Wave 1, shipped). A **proactive layer** (Wave 2) will disable input *before* the user tries. **Principle: the floor must remain correct independently of the notification/push pipeline** — chat usability must never hard-depend on a young service; push is strictly additive. For temp bans, expiry re-enables the client automatically (no unban event needed); only a *manual early unban* requires a pushed event.

**UI composition**: the moderator surface lives inside `chat-panel` (shield toolbar → `p-drawer` roster; per-message `message-mod-actions`; `ban-user-dialog`). Atomic-design split: molecules (`ban-list-item`, `ban-user-dialog`, `message-mod-actions`) + organism (`ban-list-panel`) + a room-scoped `ChatModerationService` provided at panel level (signals `bans`/`activeBans`/`bannedSubjects` + a `now` tick).

### Wave 2 — proactive push (proposed, deferred; user-gated to start)
chat-service emits `chat.moderation` (`BANNED`/`UNBANNED`) Kafka events → `notification-service` (already scaffolded: `notification_outbox` + `channel_subscription` + JWT + a Kafka consumer) persists and pushes over **SSE authenticated with a fetch-based Bearer** (`@microsoft/fetch-event-source`, decision **D1**) → notification bell + in-stream popup + `chat-panel` proactive disable. Not built until explicitly scheduled.

## Alternatives Considered

- **A1 — client parses the JWT `ent` claim** to decide moderation. Rejected: cannot evaluate the `self` scope (needs `broadcasterSubject`, deliberately withheld), and duplicates the PBAC grammar in the browser → drift the moment the backend grammar changes (as it just did in V10).
- **A2 — a dedicated `GET /rooms/{roomKey}/moderation/capability` endpoint.** Rejected for Wave 1: the room GET already runs on panel init, so a field rides it with no extra round-trip. Revisit only if capability must be queried independently of room metadata.
- **A3 — couple the chat send-gate to the notification/SSE signal** (single source of "you're banned"). Rejected: it makes core send-ability depend on notification-service uptime. The 403 floor keeps enforcement correct regardless; push is additive.
- **A4 — WebSocket for push.** Deferred in favour of SSE (unidirectional server→client, built-in reconnect, simpler, fits the WebFlux stack).
- **A5 — build the full notification/SSE stack in one release.** Deferred to a two-wave split: Wave 1 ships the reactive floor with **no new infrastructure** (already removes the raw-403 UX) and de-risks the notification epic.

## Consequences

### Positive
- Capability display is correct while PBAC ships dark and stays correct when the flag flips (same matcher, same authority). No owner-identity leak; no client-side PBAC grammar.
- Temp-bans need no scheduler; correctness is lazy and self-healing on both ends.
- Moderation travels with `chat-panel` — one integration point, no duplication across streamer/moderator surfaces.

### Negative
- `RoomResponse` is now **caller-dependent** (not cacheable by room key alone). Acceptable: it is behind auth and never cached cross-user.
- UI gating is **cosmetic** while PBAC is dark (the ban/unban endpoints are unenforced server-side) — a pre-existing accepted posture ([ADR-0004](0004-two-layer-chat-authorization.md)), not worsened here; real enforcement lands with the flag.
- The Wave-1 banned-user banner is **generic** — the `403` body carries no expiry/reason and the `moderate`-gated list endpoint is `403` for the banned user, so rich detail (duration, reason) only arrives with Wave 2's pushed payload.

### Risks
- **Client clock skew** on temp-ban countdown display — cosmetic only; the server `isActive` check is authoritative.
- **Two-wave drift**: Wave 2 could be deprioritized, leaving only the reactive floor. Accepted — the floor is a complete, correct baseline on its own.

## References
- [ADR-0004](0004-two-layer-chat-authorization.md) — two-layer chat authorization (PBAC capability + resource-state ban)
- [ADR-0005](0005-moderation-domain-condition-triggered.md) — moderation as a chat `kind`; condition-triggered posture (reused for the ban-reaper deferral)
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md)
- chat-service: `ChatAuthorization.hasCapability`, `ChatService.getRoom`, `ModerationService`, `BanSendGuard`, `RoomResponse`, `ReactiveChatBanRepository.findActiveByRoomId`
- streaming-ui: `ChatModerationService`, `chat-panel` (reactive floor + drawer), `ban-list-panel`/`ban-list-item`, `ban-user-dialog`, `message-mod-actions`, `lib/avatar`, `lib/chat-error`, `lib/time`
- `notification-service` scaffold (`notification_outbox` + `channel_subscription`) — Wave 2 substrate
- Commits `b398155`, `9a23a5f`, `c761f20` on branch `feat/chat-moderation-ux`
