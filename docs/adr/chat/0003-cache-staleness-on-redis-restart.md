# ADR-0003: Cache Staleness on Redis Restart

**Date**: 2026-07-10 (revised 2026-07-11)
**Status**: accepted
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

### Scenario C — dropped write to an otherwise-warm key (added 2026-07-11)

A third scenario surfaced during Phase 3.4/3.6 design that the original two mechanisms do **not** fully cover: **Redis is up and healthy the whole time, but a single `addToRecent` write silently fails** — a transient network blip, a timeout, a firewall/CDN hiccup, or a credential rotation causing a momentary auth failure. The write path swallows this by design (`.onErrorResume → false`), because PG is the system of record and the request must still succeed.

The danger: the room key is **non-empty** (it has prior messages), so reads do **not** miss — they serve a set that is silently *missing the dropped message*. Unlike Scenarios A/B, there is no disconnect event, so evict-on-reconnect never fires; the only recovery is TTL expiry (up to 1 hour). A single dropped write therefore serves a persistent hole for up to the TTL.

| Scenario | Redis state | Failure | Detected by |
|----------|-------------|---------|-------------|
| C. Dropped write, Redis up | Warm, serving reads | one `addToRecent` returns false | neither TTL-refresh nor reconnect — needs a dedicated mechanism |

## Decision

We implement three complementary mechanisms:

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

### 3. Evict on Write Failure (covers Scenario C)

When `addToRecent` reports failure (`false`) after a successful PG persist, the write path immediately **evicts that room key**:

```
persist to PG (success)
  → cache.addToRecent(roomKey, msg)  → false   (dropped write, Redis up)
  → cache.evictRoom(roomKey)                    (delete the now-inconsistent key)
  → next read misses → PG fallback → backfill rebuilds the complete set
```

This is the only mechanism that addresses Scenario C, because there is no disconnect (evict-on-reconnect can't fire) and the key is non-empty (no natural cache miss until TTL). Evicting converts a silent hole that would persist for up to the TTL into a single cold read on the next request.

- **Cost**: one extra `DEL` only on the (rare) write-failure path. Zero cost on the happy path.
- **Why evict rather than retry**: a retry could also fail and adds latency to the user's send; the message is already durable in PG, so the cheapest correct action is to let the next reader rebuild from the source of record.
- **Implemented in**: `ChatService.cacheWrite()` (Phase 0), not in `RedisMessageCache` — the cache stays a dumb latency buffer; the orchestration layer owns the self-heal decision.

### Combined behavior

| Event | TTL-only | + Evict-on-reconnect | + Evict-on-write-failure |
|-------|----------|----------------------|--------------------------|
| Redis blip (5s) | Stale up to 1h | Stale 0s (evicted on reconnect) | — |
| Redis crash + RDB restore (data at T-30min) | Stale up to 30min | Stale 0s (evicted on reconnect) | — |
| Redis crash, no persistence (empty after restart) | Handled — empty = cache miss | No action needed | — |
| **C. Dropped write, Redis up (no disconnect)** | **Stale up to 1h** | **Not triggered (no reconnect)** | **Stale 0s (key evicted → next read rebuilds)** |

## Deferred — Option 2: periodic reconciliation sweep

A stronger guarantee for Scenario C (and partial-write drift generally) is a **scheduled reconciliation sweep**: periodically rebuild hot-room keys from PG so any accumulated drift self-corrects on a fixed cadence, independent of read traffic.

- **Why deferred**: evict-on-write-failure already bounds Scenario C to a single cold read, and TTL bounds the worst case to 1h. A sweep adds a background component + recurring PG load for a failure mode that is already self-healing at the current scale (<50 rooms). It buys value only if drift is observed to accumulate faster than reads/TTL clear it.
- **Revisit trigger**: metrics showing repeated evict-on-write-failure events on the same rooms, or a move to persistence-heavy Redis at large room counts.
- **Marker**: `ChatService.cacheWrite()` carries a `TODO(3.x-deferred)` pointing here so a future agent can pick it up.

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

| Step | What | Status |
|------|------|--------|
| 1 | `redis.expire(key, ttl)` (`applyTtl`) in `addToRecent()` — `RedisMessageCache` | ✅ shipped (Phase 0) |
| 2 | `chat.cache.room-ttl` (Duration, default 1h) via `ChatCacheProperties` + `application.yml` | ✅ shipped (Phase 0) |
| 3 | Evict-on-write-failure in `ChatService.cacheWrite()` (Scenario C) | ✅ shipped (Phase 0) |
| 4 | `RedisReconnectListener` — `SCAN` + `DEL chat:room:*:recent` on reconnect (Lettuce `RedisConnectionStateListener`) | ✅ shipped (Phase 3.6) |
| 5 | Metrics: `cache.eviction.on_reconnect` / `cache.eviction.on_write_failure` counters | ○ deferred (6.x observability) |
| 6 | Periodic reconciliation sweep (see [Deferred](#deferred--option-2-periodic-reconciliation-sweep)) | ○ deferred (revisit on drift metrics) |

Steps 1–4 shipped together in the 3.4/3.6 branch. Reconnect eviction (step 4) exposes
`evictAllRooms()`; the production trigger registers a Lettuce connection-state listener,
guarded so a wiring failure never blocks context startup.

> **Integration-test caveat (2026-07-11):** the Testcontainers suites that exercise TTL,
> evict-on-reconnect, and evict-on-write-failure are written but were **not executed** in
> the authoring environment (no Docker daemon available). They compile; run
> `./gradlew :chat-service:test` on a Docker-enabled host to validate.

## References

- [ADR-0001](0001-cache-aside-redis-zset.md) — Cache-aside with Redis ZSET (the pattern this ADR extends)
- [ADR-0000](0000-architecture-foundation.md) — Chat service architecture (Redis role: "Hot cache — disposable latency buffer")
- [common/ADR-0002](../../adr/common/0002-redis-ephemeral-data-store.md) — Redis as ephemeral data store (disposable by design)
- `RedisMessageCache.java` — current cache implementation with `.onErrorResume()` resilience
