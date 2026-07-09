# ADR-0003: Cache Staleness on Redis Restart

**Date**: 2026-07-10
**Status**: proposed
**Deciders**: hieuht, Claude

## Context

The chat service uses a cache-aside pattern with Redis ZSETs ([ADR-0001](0001-cache-aside-redis-zset.md)). When Redis is unavailable, writes go to PostgreSQL only and reads fall back to PG gracefully — this part works correctly (verified 2026-07-10).

The problem arises when Redis **restarts with persisted data** (AOF/RDB). Redis restores its snapshot from before the outage. During the outage window, new messages were written to PG but never cached. After restart, Redis serves the **stale snapshot** — missing messages written during the outage and potentially including messages that have since been deleted or modified. There is no mechanism to detect or correct this staleness except waiting for the next write to that specific room key (which re-populates that key gradually).

Two failure scenarios:

| Scenario | Redis before outage | During outage | After restart |
|----------|-------------------|---------------|---------------|
| A. Brief network blip | Healthy, up-to-date | 5 PG-only writes to room A | Stale — missing those 5 messages |
| B. Redis crash + RDB restore | Snapshot at T-30min | 50 PG-only writes across 3 rooms | Stale — missing all 50 messages; oldest stale window = 30 min |

In both scenarios, the read path (`cache.getRecent() → cache miss via .onErrorResume`) works correctly while Redis is down. The problem is only _after_ Redis comes back — the cache now has data again, so there is no cache miss, but the data is incorrect.

This is a known limitation of the cache-aside pattern when the cache persists its state across restarts. ADR-0001 §"Negative" acknowledges eventual consistency but only in the context of a single write failure, not a whole-cache staleness window.

## Decision

We will implement two complementary mechanisms, in order:

### 1. Key-Level TTL (immediate safety net)

Set a TTL on every room ZSET key on each write:

```
EXPIRE chat:room:{roomKey}:recent 3600  (1 hour, configurable)
```

This bounds the maximum staleness window to 1 hour regardless of Redis persistence. After expiry, the key is deleted by Redis, the next read is a cache miss, and the PG fallback + backfill path rebuilds it.

- **Cost**: one additional Redis command per write (`EXPIRE`). Negligible.
- **Configurable**: `chat.cache.ttl-hours` in `application.yml`. Higher for low-traffic rooms (less frequent writes → longer cache benefit), lower for high-traffic rooms (fresher data after outage).
- **Edge case**: a room with zero writes for >1 hour loses its hot cache entirely. Acceptable — the next reader pays a PG round-trip to rebuild it.

### 2. Evict on Redis Reconnect (defense in depth)

Listen for Redis connection re-establishment via Spring Data Redis's `ReactiveRedisConnectionFactory` lifecycle events. On reconnect, scan for all `chat:room:*:recent` keys and delete them.

```
Redis reconnect detected
  → SCAN chat:room:*:recent
  → DELETE each key
  → Rooms warm back up on next read (PG fallback → async backfill)
```

This costs one `SCAN` + N `DEL` operations per reconnect event. Typically 0–2 reconnects per day in a stable environment.

- **Why delete instead of backfill**: Scanning + backfilling every room from PG on reconnect could cause a thundering herd on PG. Deleting is a single Redis operation per key; the backfill is distributed across actual reader requests.
- **Why not Redis keyspace notifications**: `__keyspace@0__:chat:room:*` events could detect expiry and trigger backfill, but they're fire-and-forget with no delivery guarantee. A reconnect event is a single deterministic trigger point.

### Combined behavior

| Event | TTL-only | TTL + Evict-on-reconnect |
|-------|----------|--------------------------|
| Redis blip (5s) | Stale for up to 1h | Stale for 0s (evicted on reconnect) |
| Redis crash + RDB restore (data at T-30min) | Stale for up to 30min (keys unexpired) | Stale for 0s (evicted on reconnect) |
| Redis crash, no persistence (empty after restart) | Already handled — empty cache = cache miss | No action needed |

## Alternatives Considered

### Alternative 1: Redis persistence disabled (pure cache)

Set `save ""` in Redis config — no RDB snapshots, no AOF. Redis is always empty after restart.

- **Pros**: No staleness problem to solve. Matches the ADR-0001 philosophy ("cache is disposable").
- **Cons**: Cold start after every restart — every active room pays PG fallback on first read. For a pet project with 1–5 rooms this is fine; for a production service with 1000+ rooms it creates a brief PG load spike.
- **Why not**: This is a valid option and may be the right one for _this phase_. We choose TTL + eviction instead because (a) it works with either persistence config, (b) the implementation is small, and (c) it's the same pattern we'd use at scale.

### Alternative 2: CDC-based cache warming (Debezium + Kafka)

PostgreSQL change-log → Kafka topic → dedicated cache-warmer service → Redis.

- **Pros**: Redis is always consistent with PG, bounded only by CDC lag (~100ms). Handles reconnect, cold start, and schema evolution.
- **Cons**: Requires Kafka Connect + Debezium + a new cache-warmer service. Operational complexity far beyond Phase 3 scope.
- **Why not**: Deferred to Phase 6+ (production hardening). The pattern is noted here so the upgrade path is clear.

### Alternative 3: Versioned cache keys

Include a generation counter in the key: `chat:room:{roomKey}:recent:gen-{N}`. On reconnect, increment the generation → old keys naturally expire via TTL → new reads use the new generation.

- **Pros**: No explicit scan+delete on reconnect. Old keys die naturally.
- **Cons**: The generation counter itself must survive Redis restarts or be managed externally. Adds complexity to key construction.
- **Why not**: Over-engineered for the current scale. TTL + evict-on-reconnect achieves the same effect with no mutable global state.

## Consequences

### Positive
- **Bounded staleness**: Maximum window is 1 hour (TTL), typically 0 seconds (evict on reconnect).
- **Self-healing**: No operator intervention needed after Redis outage.
- **Progressive deployment**: TTL can be implemented first (one line per write) and works independently. Evict-on-reconnect can follow.
- **Observable**: Log messages for eviction events and TTL expiry make staleness windows visible in logs.

### Negative
- **TTL drift**: Low-traffic rooms may lose their hot cache if no one writes for >1 hour. **Mitigation**: configure TTL per environment; set higher (24h) for production if traffic patterns warrant it.
- **Reconnect eviction is coarse**: All rooms evicted on any reconnect, even if Redis was only down for 2 seconds and had no data loss. **Mitigation**: the eviction is a `SCAN` + `DEL` on keys that are almost certainly already expired or will be on next write. The cost is a brief cold-read spike, which is acceptable for an infrequent event.

### Risks
- **Thundering herd on reconnect eviction**: If 500 rooms are evicted simultaneously and 500 viewers load their rooms within the same second, PG sees 500 `findByExternalKey + findByRoomIdOrderByCreatedAtDesc` queries. **Mitigation**: stagger the backfill with a small per-room delay, or use a `Mono.cache()` on the PG result for a brief window. Current scale (<50 rooms) does not warrant this complexity.
- **TTL not respected by Redis <2.6**: The `EXPIRE` command exists since Redis 1.0. Not a concern.

## Implementation Plan

| Step | What | Effort | Phase |
|------|------|--------|-------|
| 1 | Add `redis.expire(key, ttl)` to `addToRecent()` in `RedisMessageCache` | 1 line | 3.x (next) |
| 2 | Add `chat.cache.room-ttl-hours` to `application.yml` (default 1) | 2 lines | 3.x (next) |
| 3 | Add `RedisReconnectListener` — `@EventListener` on reconnect, scan + delete `chat:room:*:recent` keys | ~30 lines | 3.x or 6.x |
| 4 | Add metrics: `cache.eviction.on_reconnect` counter | 2 lines | 6.x (observability) |

Steps 1–2 are the immediate fix and can ship in any 3.x commit. Steps 3–4 can follow when reconnect handling is prioritized.

## References

- [ADR-0001](0001-cache-aside-redis-zset.md) — Cache-aside with Redis ZSET (the pattern this ADR extends)
- [ADR-0000](0000-architecture-foundation.md) — Chat service architecture (Redis role: "Hot cache — disposable latency buffer")
- [common/ADR-0002](../../adr/common/0002-redis-ephemeral-data-store.md) — Redis as ephemeral data store (disposable by design)
- `RedisMessageCache.java` — current cache implementation with `.onErrorResume()` resilience
