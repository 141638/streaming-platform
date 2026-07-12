# ADR-0008: Chat Moderation Wave 1.1 — Modify-Ban-Duration In Place, `viewerBanned` Room Metadata, and Config-Only Perf/Log Hardening

**Date**: 2026-07-12
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

Wave 1 shipped the moderator drawer, the reactive `403 CHAT_USER_BANNED` enforcement floor, temp-bans, and the `viewerCanModerate` capability signal ([ADR-0006](0006-moderation-ux-capability-and-push-split.md)). Hands-on use surfaced a batch of defects and gaps that Wave 1.1 closes — all *within* the shipped enforcement-floor architecture; the proactive push layer stays gated ([ADR-0007](0007-proactive-push-infrastructure-gated.md)). Four decisions here are worth recording because they set precedent beyond the immediate fix:

1. **Changing a ban's duration required unban + re-ban** — two moderator actions, two rows touched, and (once push lands) two user notifications for what is conceptually one edit.
2. **A banned user only learned they were banned by *trying to send*** — the floor is reactive (403 on send). Room metadata already carried `viewerCanModerate`; the banned state had no equivalent load-time signal.
3. **The ban roster showed raw JWT `sub`s, not names** — and chat-service (owning `chat_ban`) has no access to auth-service's user table. This is the recurring "id-only row, display needs the name" problem (previously hit by `chat_message.author_username`).
4. **GET room/messages/bans took seconds, and the Kafka consumer flooded logs** — with no broker running. Investigation ruled out the auth guard/JWKS (chat-service decodes JWTs locally via HMAC; PBAC ships dark). The cause is the gateway↔Eureka path + an untuned R2DBC pool, and an auto-started `@KafkaListener` with no broker.

## Decision

### D1 — Modify ban duration in place (`PATCH /v1/rooms/{roomKey}/bans/{subject}`)

Add a moderator capability to **re-base an existing ban's `expiresAt` off *now*** instead of unban + re-ban. The handler loads the `(room, subject)` ban, mutates `expiresAt = now + durationSeconds` (`null` ⇒ permanent) on the **loaded** entity, and `save()`s it. Because a hydrated R2DBC entity has `isNew=false`, `save()` issues an **UPDATE**, preserving the row and dodging the `unique (room_id, banned_subject)` trap a delete+insert would risk. `404 CHAT_BAN_NOT_FOUND` when the subject has no ban. The client edits along an absolute preset ladder `[1h, 24h, 7d, permanent]`, deriving the current rung from `round((expiresAt − createdAt)/preset)`.

The real-time **notification** of the duration delta (increase / decrease / lift, with the exact new unban time) is **Wave-2/ADR-0007 deferred** — a `// Wave 2 (ADR-0007)` emit-marker sits in `updateBanDuration`. Wave 1.1 ships only the moderator capability; a shortened or lifted duration still resolves client-side on the `now`-tick.

### D2 — `viewerBanned` on room metadata

`RoomResponse` gains `viewerBanned`, resolved in `getRoom` alongside `viewerCanModerate` (composed with `Mono.zip`) from the caller's active ban (`jwt == null ⇒ false`). The banned user sees the disabled input + banner **on room load**, independent of the (future) push pipeline. The 403 floor stays as the backstop. Both this signal and `BanSendGuard` delegate to a single **`ChatBan.isActive(now)`** source of truth (the SQL `findActive` predicate mirrors it).

### D3 — Embed cross-service display fields; do not cross-service look up

Store `banned_username` + `banned_by_username` **on `chat_ban`** (migration `V5`), captured at **write time** from data the caller already holds: the client supplies `bannedUsername` (it has the message author's name); the moderator name comes from the JWT `attr.username`. Nulls / older rows fall back to a truncated subject. This generalizes the `chat_message.author_username` precedent into a **standing convention**: *a service embeds a denormalized copy of another service's display fields rather than storing an id-only reference and resolving it across services at read time.* Avatars are **not** stored — they are deterministically derived from the subject (DiceBear), so a copy would be redundant; a real *uploaded* avatar would be embedded under this same rule.

### D4 — Config-only perf + log-noise hardening

All reversible, no code path changed:
- **Kafka**: `spring.kafka.listener.auto-startup: ${KAFKA_LISTENER_ENABLED:false}` (env-gated — no broker is owned yet, ADR-0007) + `logging.level` `NetworkClient`/`consumer.internals` → `ERROR`.
- **R2DBC pool**: `validation-query: SELECT 1`, `max-acquire-time: 3s`, `max-idle-time: 5m` — validate liveness so a firewall/PG-dropped socket in the default 30-min idle window doesn't stall the first query.
- **Gateway**: `httpclient.connect-timeout: 2000`, `response-timeout: 10s` — bound a black-holed Eureka instance instead of Spring Cloud Gateway's 45s default connect / unbounded response.

## Alternatives Considered

- **Duration change via unban + re-ban (no new endpoint).** Rejected: two actions, two future notifications, and it churns the row identity for what is one logical edit.
- **Delete-then-insert inside `updateBanDuration`.** Rejected: risks the `unique (room_id, banned_subject)` constraint and loses `created_at`/history; mutating the loaded row (`isNew=false` ⇒ UPDATE) is cleaner.
- **Resolve usernames via a cross-service call to auth-service at ban/read time.** Rejected: runtime cross-service coupling + N-lookups over the network, exactly the failure mode this ADR's D3 convention exists to avoid. The client already holds the name.
- **Store the avatar URL on `chat_ban` too.** Rejected as YAGNI: the avatar is a pure function of the subject today; embed it only when real uploaded avatars exist.
- **Chase the slow-API cause in the auth guard / add caching there.** Rejected: the guard decodes JWTs locally (no network); measurement pointed at the gateway↔Eureka path + pool liveness, which config fixes address without touching auth.

## Consequences

### Positive
- One moderator action to change a ban; the `(room, subject)` row and its `created_at` survive.
- Banned users get immediate, load-time feedback without depending on the notification pipeline.
- The roster reads with human names, with zero cross-service calls on the hot path.
- Slow-API and log-noise causes are addressed reversibly, with no behavioural code change.

### Negative
- **Denormalized usernames go stale on rename.** Accepted tradeoff: the `subject` remains the identity; the embedded name is display-only. Same posture as `chat_message.author_username`.
- **`updateBanDuration` overwrites `expiresAt` with no audit history** of prior durations. A duration-change audit trail is **condition-deferred** — add only if an audit requirement appears.
- **`auto-startup: false` makes `StreamControlListener` inert locally** — `STREAM_CREATED` room-lifecycle won't fire unless `KAFKA_LISTENER_ENABLED=true`. Intentional until a broker is owned (ADR-0007); noted so it isn't a surprise.

### Risks
- **The global gateway `response-timeout: 10s` will sever the future notifications SSE stream.** When Wave 2 lands, `/api/notifications/stream` must be excluded from the global timeout (per-route `response-timeout: -1` or a dedicated route). Tracked in the [Wave-2 plan](../../plans/chat-moderation-wave2-proactive-push.md) risk table.
- **Perf fix targets the most-likely cause, not an empirically isolated one.** The direct-call-vs-gateway discriminating measurement should be run before declaring the slowness closed.

## References
- [ADR-0006](0006-moderation-ux-capability-and-push-split.md) — moderation UX; enforcement-floor / proactive-push split
- [ADR-0007](0007-proactive-push-infrastructure-gated.md) — the gate that keeps the duration-delta *notification* deferred
- [ADR-0002](0002-jwt-derived-author-identity.md) — the `chat_message.author_username` precedent D3 generalizes
- [chat-moderation-wave2-proactive-push.md](../../plans/chat-moderation-wave2-proactive-push.md) — where the `updateBanDuration` emit-point and the SSE-vs-timeout risk are tracked
- `V5__chat_ban_usernames.sql`, `ChatBan.isActive`, `ModerationService.updateBanDuration`, `ChatService.getRoom`
