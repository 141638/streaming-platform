# ADR-0001: Cache-Aside Pattern with Redis ZSET for Chat Messages

**Date**: 2026-07-02
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

Chat messages have a dual access pattern: (1) high-frequency reads for the "latest N messages" (hot path — every viewer entering a room loads recent chat), and (2) low-frequency reads for historical scrolling and compliance (warm/cold path). PostgreSQL alone can serve both, but at scale the hot path would hit PG repeatedly for the same data. We need a caching strategy that offloads the hot path without risking data loss — chat messages are durable content, not ephemeral presence.

The platform already provides Redis as infrastructure. The question is: what data structure, what caching pattern, and what consistency model?

Key constraints from [`docs/SERVICE-ARCHITECTURE.md`](../../SERVICE-ARCHITECTURE.md) §2:
- PostgreSQL schema `chat` is the system of record
- Redis is a latency buffer, not primary storage
- Ordered message retrieval per room

## Decision

We use the **cache-aside pattern** with a **Redis ZSET** per room for the hot cache.

- **Data structure**: `ZSET` keyed as `chat:room:{roomKey}:recent`, scored by epoch-millis (`message.createdAt`). This naturally supports both "latest N" (reverse range by score) and cursor-based pagination (range by score with a cursor timestamp).
- **Write path**: PG first, then Redis. `ChatService.sendMessage()` persists to PostgreSQL via `messageRepository.save()`, then adds the serialized `MessageResponse` to the ZSET. If Redis fails after PG commit, the message is still durable — it just won't appear in the hot cache until the next read-path backfill.
- **Read path**: Redis first, PG fallback. `ChatService.getRecentMessages()` queries the ZSET via `ZREVRANGEBYSCORE` with an unbounded range. On cache miss (empty list), it loads from PG and asynchronously backfills Redis without blocking the response.
- **Retention**: 100 messages per room. On each write, `ZREMRANGEBYRANK` trims entries beyond the cap.
- **Serialization**: Jackson JSON with `JavaTimeModule` for `OffsetDateTime` support.

The pattern is already documented as the recommendation in [`docs/SERVICE-ARCHITECTURE.md`](../../SERVICE-ARCHITECTURE.md) §2.

## Alternatives Considered

### Alternative 1: Redis LIST (LPUSH + LTRIM)
- **Pros**: Simpler API — `LPUSH` for append, `LTRIM` for cap, `LRANGE` for read. No score management.
- **Cons**: No native time-based cursor pagination. To paginate beyond the cap, you must fall back to PG anyway — and the LIST has no timestamp to align with PG ordering. LIST is opaque blobs; ZSET scores are self-describing.
- **Why not**: ZSET's epoch-millis score is cheap and gives us cursor pagination for free when we need it (Phase 3.5+ chat history scrolling).

### Alternative 2: Redis Streams (XADD + XREAD)
- **Pros**: Purpose-built for messaging. Consumer groups, acknowledgment, replay — essentially Kafka-lite inside Redis. Would support fan-out to WebSocket workers naturally.
- **Cons**: Operational complexity — consumer group state, pending entries list, explicit ACK. Streams are the right answer for real-time fan-out (Phase 4: WebSocket delivery), not for the hot-cache use case. Overlapping Streams + ZSET means two Redis data structures per room.
- **Why not**: Not rejected — deferred. Streams are the natural choice for Phase 4 (real-time message delivery to connected viewers). For Phase 3 (REST request/response chat), ZSET is simpler and sufficient.

### Alternative 3: Write-through cache (Redis first, async PG write)
- **Pros**: Lower write latency — client waits for Redis, not PG.
- **Cons**: Redis becomes the system of record in the write path. If Redis crashes between accepting a write and the async PG flush, messages are lost. This violates the "PG is always correct" constraint.
- **Why not**: Durability beats write latency for chat. Messages must survive Redis restarts. The PG-first approach adds ~2-5ms but guarantees no data loss.

### Alternative 4: No cache — PG-only with read replicas
- **Pros**: Architectural simplicity. No cache invalidation, no consistency concerns, one less infrastructure dependency.
- **Cons**: The hot path ("latest 50 messages per room") is an indexed query on PG anyway (`WHERE room_id = ? ORDER BY created_at DESC LIMIT 50`) — it performs well. But at scale (100+ concurrent viewers hitting refresh on room entry), every PG read is a round-trip that could have been a sub-millisecond Redis hit.
- **Why not**: Redis is already in the platform. The cache-aside pattern is ~80 lines in `RedisMessageCache`. The cost of adding it now is low; the cost of retrofitting it after PG becomes a bottleneck is higher.

## Consequences

### Positive
- **Hot-path latency**: Recent-message reads hit Redis (sub-millisecond) instead of PG (~2-5ms). This matters when multiple viewers load a room simultaneously.
- **Natural pagination**: ZSET scores (epoch-millis) align with PG `created_at` ordering — a cursor in Redis can be reused for PG fallback seamlessly.
- **Cache is disposable**: `FLUSHDB` in Redis loses nothing durable. The next read request falls back to PG and backfills.
- **Observability**: Cache hit/miss is logged per request — easy to monitor hit rate and detect when retention cap is too small.

### Negative
- **Eventual consistency**: Redis can be stale or empty. A message written to PG may not appear in the hot cache if the Redis write fails. **Mitigation**: the read-path backfill corrects this on next request; the gap is at most one request window.
- **Duplicate serialization**: Every cache write JSON-serializes a `MessageResponse` that was just constructed from a `ChatMessage` that was read from PG. **Mitigation**: acceptable — serialization cost is negligible compared to the PG round-trip it saves on reads.
- **No cross-room queries**: ZSETs are per-room by design. "All messages by user X across rooms" must go to PG. This is intentional — cross-room queries are moderation/compliance paths that belong in PG.

### Risks
- **Cache stampede on cold start**: If Redis is empty and 100 viewers load the same room simultaneously, all 100 fall back to PG. **Mitigation**: low risk at current scale. If it becomes an issue, add a `Mono.cache()` or a small TTL-based lock around the backfill path.
- **ZSET size drift**: If the retention trim fails (network blip), ZSETs grow unbounded. **Mitigation**: `trimToRetention` is called on every write in the same Redis pipeline; monitor ZSET cardinality via `ZCARD` in health checks.

## Refinements

### 2026-08-01 — Atomic Lua Script for Cache Writes (C1)

The original 3-step write pipeline (ZADD → ZREMRANGEBYRANK → EXPIRE,
three separate network round-trips with no atomicity) was replaced with
a single EVALSHA call to `add_to_recent.lua`. The Lua script executes
the same three operations atomically, eliminating a race window where
two concurrent writers could interleave ZADD and ZREMRANGEBYRANK.

- **Script**: `chat-service/src/main/resources/redis/add_to_recent.lua`
- **Pattern**: `DefaultRedisScript<Long>` loaded via `ClassPathResource`
  in the constructor, matching `gateway-service:RateLimitFilter`
- **Fallback**: Spring Data Redis' `ScriptExecutor` transparently falls
  back from EVALSHA to EVAL when the script SHA is missing (e.g., after
  Redis restart)
- **Atomicity**: With Lua, the ZADD+trim+EXPIRE sequence is one atomic
  Redis operation — two concurrent writers each get their own script
  execution; no interleaving is possible
- **Plan**: [C1 — Redis Lua Atomic Cache](../../plans/C1-redis-lua-atomic-cache.md)
