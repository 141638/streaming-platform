# ADR-0011: Message Idempotency via `client_id` Unique Constraint

**Date**: 2026-08-01
**Status**: accepted
**Deciders**: 141638, Claude

## Context

Chat messages have no deduplication at the service or database layer. The only
idempotency defense is the gateway's `IdempotencyFilter`, which caches 2xx
responses by `Idempotency-Key` header in Redis for 24 hours and replays them on
cache hit. This works for the REST path but has three gaps:

1. **WebSocket bypass**: ADR-0010 makes WebSocket the primary send path, and
   WebSocket frames bypass the gateway entirely. The `clientId` already carried
   in send frames (`{ type: "send", clientId: "client-{ts}-{n}", content }`)
   is the natural idempotency key — but nothing enforces it at persistence time.

2. **No defense in depth**: The `chat_message` table has no unique constraint
   on any business column. Two identical sends always produce two rows with
   different UUIDs. Compare: `chat_room` has `uq_chat_room_external_key` and
   `chat_ban` has `uq_chat_ban_room_subject`. Messages are the only chat
   resource without a database-level uniqueness guarantee.

3. **Internal path unprotected**: `sendSystemMessage()` (called from
   `StreamControlListener` via Kafka) bypasses both the gateway and any
   client-provided idempotency key. Duplicate Kafka events (redelivery during
   rebalance) could produce duplicate system messages.

The WebSocket path is the primary concern — it will carry the majority of
message traffic post-ADR-0010, and the gateway filter provides zero protection.

## Decision

Add a `client_id VARCHAR(64)` column to `chat_message` with a **unique index**,
making the database the authoritative idempotency enforcer. The `clientId` from
WebSocket send frames (and the `Idempotency-Key` header from REST fallback
requests) is stored on the message entity and checked via the unique constraint
at insert time.

On `DuplicateKeyException`:
1. Query the existing message by `client_id`
2. Return it as the response — the client sees the same confirmed message
3. **Skip the cache write** — the message was already cached on the first
   successful insert (C1's Lua script guarantees the cache write is atomic with
   persist, so the cache is already correct)

This gives two-layer idempotency:

| Layer | Path | Mechanism | Window |
|-------|------|-----------|--------|
| Gateway (fast) | REST fallback only | Redis cache of 2xx response by key | 24h |
| Database (hard) | All paths (WS + REST + system) | `UNIQUE(client_id)` | Forever |

The gateway cache still serves the REST fallback path — fast retries (double-
click, network replay) never reach the service. The DB constraint is the
belt-and-suspenders: it catches everything the gateway misses (WS sends, system
messages, gateway Redis failure, retries after the 24h cache window).

For system messages (`sendSystemMessage()`), a deterministic key is derived:
`system:{roomId}:{eventId}` where `eventId` is the Kafka event identifier
(partition + offset or message key). This makes system-message idempotency
automatic — duplicate Kafka events hitting `sendSystemMessage()` will produce
the same `client_id` and be rejected by the unique constraint.

### `clientId` vs. `idempotency_key`

ADR-0010 already established `clientId` as the idempotency key for WebSocket
sends ("reconnect replay with same `clientId` returns cached response"). Using
the same name for the database column keeps the vocabulary consistent across
WebSocket frames, REST headers, and the persistence model.

## Alternatives Considered

### Alternative 1: Redis-based dedup only (SETNX window)

- **Pros**: No migration, no new column. Matches the WebSocket ADR's stated
  approach ("60s dedup window with cached response").
- **Cons**: Dedup window evaporates on Redis restart. Doesn't protect against
  a retry arriving after the window closes. `sendSystemMessage()` needs a
  separate mechanism or remains unprotected. Weaker guarantee than a database
  constraint — Redis is disposable, PG is the system of record.
- **Why not**: Redis is a fast cache for the dedup window; it shouldn't be the
  sole authority. The unique constraint is the ground truth — SETNX can be
  added later as a pre-check optimization if the DB unique-constraint violation
  path shows measurable latency.

### Alternative 2: Composite unique constraint on business columns

`UNIQUE(room_id, author_subject, body, created_at_truncated_to_minute)`

- **Pros**: No new column needed. Catches true duplicates without requiring
  client cooperation.
- **Cons**: Fragile — same user sending "hello" twice in the same minute
  SHOULD produce two messages (they're distinct conversation events). The
  client knows intent better than the server does. Also, `created_at` is
  server-assigned and can collide under concurrent writes.
- **Why not**: False positives on legitimate rapid-fire messages. The client
  is the authority on "is this a retry or a new message" — the `clientId`
  model respects that.

### Alternative 3: Application-level dedup in ChatService (no DB constraint)

Check `messageRepository.existsByClientId(clientId)` before insert.

- **Pros**: No migration, same effect as unique constraint.
- **Cons**: Race condition — two concurrent inserts with the same `clientId`
  both pass the existence check before either commits. The unique constraint
  is atomic at the database level; the existence check is not. Only the
  constraint provides correctness under concurrency without application-level
  locking (`SELECT ... FOR UPDATE` or `SETNX` in Redis).
- **Why not**: Race condition defeats the purpose. The unique constraint is
  simpler (no extra query) and correct by construction.

## Consequences

### Positive

- **All paths protected**: WebSocket, REST fallback, and internal system
  messages all get idempotency through the same mechanism.
- **Correct under concurrency**: The database unique constraint is atomic —
  two concurrent sends with the same `clientId` cannot both succeed.
- **Cache naturally consistent**: The write path is PG-first → cache-second.
  A unique-constraint violation means PG didn't persist, so the cache is
  never touched — no stale or duplicate cache entries.
- **No application-level locking**: No `SETNX`, no `SELECT ... FOR UPDATE`,
  no distributed lock. The database handles it.
- **Works with C1 Lua refactor**: The Lua script (`add_to_recent.lua`) runs
  only after a successful PG insert — the unique constraint guarantees at most
  one cache write per `clientId`.

### Negative

- **Migration required**: New column + unique index on `chat_message`. For
  existing rows without a `clientId`, the column is nullable — the unique
  index only covers non-null values (`WHERE client_id IS NOT NULL`), so
  historical data is unaffected. New messages always provide a `clientId`.
- **`clientId` format constraint**: Clients must generate unique, stable
  `clientId` values per send attempt. The existing `client-{ts}-{counter}`
  format from the optimistic-send pattern already satisfies this.

### Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Client generates colliding `clientId` values | Low | The `client-{ts}-{counter}` format (epoch-millis + incrementing counter) is collision-resistant within a session. Cross-session collisions are harmless — different users can't send messages with each other's `clientId` (different `authorSubject` → different rows, even if `clientId` collides... *but the unique constraint would still reject the second*). Add `authorSubject` to the unique index if cross-user collision is a concern. |
| `sendSystemMessage` has no event ID | Low | Kafka `ConsumerRecord` provides `topic() + partition() + offset()` — concatenated as the deterministic key. If a non-Kafka caller uses `sendSystemMessage`, pass `null` to skip idempotency (acceptable for one-off admin operations). |
| Existing messages (NULL `client_id`) vs. new messages | Low | Use a partial unique index: `CREATE UNIQUE INDEX ... ON chat_message (client_id) WHERE client_id IS NOT NULL`. NULL values are not considered equal, so existing rows coexist fine. |

## Deferred

- **Redis SETNX pre-check**: A fast Redis `SETNX` before the PG insert could
  avoid hitting the DB for obvious duplicates (same `clientId` within 5
  minutes). Adds marginal latency improvement at the cost of Redis dependency
  in the idempotency path. Not needed until duplicate-send volume is measurable.
- **Idempotency for moderation actions**: `ModerationService.ban()` already
  has natural idempotency via `(room_id, banned_subject)` unique constraint.
  No changes needed.

## Implementation Plan

| # | What | Where |
|---|------|-------|
| 1 | Forward `Idempotency-Key` / `X-Client-Id` header downstream | Gateway `IdempotencyFilter` |
| 2 | New migration: `client_id VARCHAR(64)` + partial unique index | `chat-service` Flyway V7 |
| 3 | `clientId` field on entity | `ChatMessage.java` |
| 4 | Accept `clientId` from WS frame + REST header, pass to service | `ChatController`, `ChatWebSocketHandler` |
| 5 | `sendMessage()` signature: add `@Nullable String clientId` parameter | `ChatService.java` |
| 6 | Catch `DuplicateKeyException` in `persistAndCache`, return existing | `ChatService.java` |
| 7 | Deterministic key for `sendSystemMessage()` | `StreamControlListener`, `ChatService.java` |

**Status**: implemented — all 7 items complete (2026-08-01). See [retrospective](../../plans/chat-idempotency-c1-retrospective.md).

## References

- [ADR-0010](0010-websocket-real-time-messaging.md) — WebSocket real-time messaging (defines `clientId` in send frames)
- [ADR-0001](0001-cache-aside-redis-zset.md) — Cache-aside pattern (the PG-first write path this ADR reinforces)
- [C1 Plan](../../plans/C1-redis-lua-atomic-cache.md) — Lua atomic cache write (complementary — fixes *how* we cache; this ADR fixes *whether* we cache)
- [Gateway IdempotencyFilter](../../../main/source/backend/gateway-service/src/main/java/com/streaming/gateway/filter/IdempotencyFilter.java) — existing gateway-level idempotency
