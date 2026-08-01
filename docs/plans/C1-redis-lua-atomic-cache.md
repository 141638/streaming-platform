# Phase C1 -- Redis Lua Script for Atomic Cache Writes

## Status: complete (2026-08-01)

## Context

`RedisMessageCache.addToRecent()` in chat-service executes three separate Redis
commands via chained `.flatMap()`:

1. `ZADD key score json`
2. `ZREMRANGEBYRANK key 0 -101`
3. `EXPIRE key roomTtl`

Each is an independent network round-trip with no atomicity. Two concurrent
writers can interleave their ZADD and ZREMRANGEBYRANK, causing one writer to
accidentally trim messages the other just added. The race window is small but
real under concurrent fanout (e.g. two SSE event-handler threads writing to the
same room key).

The project already has two Lua scripts using the same ZADD+trim+EXPIRE pattern
atomically, and one follows the exact `DefaultRedisScript` + `ClassPathResource`
constructor-load pattern that chat-service will adopt:

- `gateway-service/src/main/resources/redis/rate_limit.lua` -- ZREMRANGEBYSCORE
  + ZCARD + ZADD + EXPIRE
- `auth-service/src/main/resources/redis/rotate_refresh_token.lua`

## Solution

Write `add_to_recent.lua` and invoke it via `EVALSHA` in `RedisMessageCache`,
replacing 3 round-trips with 1 atomic operation. Spring Data Redis'
`ScriptExecutor` handles the EVALSHA-to-EVAL fallback transparently when the
script SHA is missing (e.g. after a Redis restart), so no application-level
retry logic is needed.

### Lua script

```lua
-- add_to_recent.lua
-- KEYS[1] = chat:room:{roomKey}:recent
-- ARGV[1] = score (epoch millis)
-- ARGV[2] = JSON message body
-- ARGV[3] = retention cap (e.g. 100)
-- ARGV[4] = TTL seconds

redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
local stop = -(tonumber(ARGV[3]) + 1)
redis.call('ZREMRANGEBYRANK', KEYS[1], 0, stop)
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
```

### Script location

```
chat-service/src/main/resources/redis/add_to_recent.lua
```

Following the existing `resources/redis/` convention established by gateway and
auth services.

## Tasks

| # | Task | Files | Action |
|---|------|-------|--------|
| 1 | Create Lua script | `chat-service/src/main/resources/redis/add_to_recent.lua` | CREATE |
| 2 | Load and invoke script in `RedisMessageCache` | `chat-service/src/main/java/.../infrastructure/cache/RedisMessageCache.java` | MODIFY -- load script via `DefaultRedisScript<Long>` in constructor (same pattern as `RateLimitFilter`), replace the 3-step `.flatMap()` chain in `addToRecent()` with a single `redis.execute(script, keys, args)` call. Remove the now-unused `trimToRetention()` and `applyTtl()` private methods. |
| 3 | Build verification | `./gradlew :chat-service:compileJava` | Verify `DefaultRedisScript` compiles with existing dependencies (spring-boot-starter-data-redis-reactive already depends on spring-data-redis, which provides the class) |
| 4 | Run existing tests | `./gradlew :chat-service:test` | Verify no regressions in `RedisMessageCacheTest` (retention, TTL, ordering, resilience) |

### Task 2 detail -- Java changes

**Constructor change** -- load the script once at bean creation:

```java
private final RedisScript<Long> addToRecentScript;

public RedisMessageCache(ReactiveStringRedisTemplate redis, ChatCacheProperties cacheProperties) {
    this.redis = redis;
    this.cacheProperties = cacheProperties;
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    // load Lua script from classpath (same pattern as RateLimitFilter)
    String lua;
    try {
        lua = new ClassPathResource("redis/add_to_recent.lua")
                .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
        throw new UncheckedIOException("Failed to load add_to_recent.lua", e);
    }
    this.addToRecentScript = new DefaultRedisScript<>(lua, Long.class);
}
```

**addToRecent() rewrite** -- replace the 3-step chain:

```java
public Mono<Boolean> addToRecent(String roomKey, MessageResponse message) {
    String key = recentKey(roomKey);
    String json = serialize(message);
    if (json == null) {
        return Mono.just(false);
    }
    double score = message.createdAt().toInstant().toEpochMilli();
    return redis.execute(addToRecentScript,
                    List.of(key),
                    List.of(
                            String.valueOf((long) score),
                            json,
                            String.valueOf(MAX_RECENT),
                            String.valueOf(cacheProperties.roomTtl().getSeconds())))
            .next()  // Flux<Long> -> Mono<Long> (script returns a single Long)
            .map(result -> result != null && result > 0)
            .timeout(REDIS_TIMEOUT)
            .onErrorResume(ex -> {
                log.warn("Redis write failed for key={}, message already persisted to PG. Error: {}",
                        key, ex.getMessage());
                return Mono.just(false);
            });
}
```

**Remove** the private methods `trimToRetention()` and `applyTtl()` -- their
logic has moved into the Lua script.

**Imports to add:**

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
```

## Design Notes

### Why not batch the bulk backfill

The backfill path (`ChatService.loadRecentMessages()`) issues up to 30
individual `addToRecent()` calls on a cache miss, one per message loaded from
PostgreSQL. Batching these into a single Lua call would require a different
script signature (variadic KEYS/ARGV or JSON-encoded batch), add complexity for
marginal gain, and obscure the hot-path win. The main win is hot-path latency
(3 RTT becomes 1) and atomic correctness; backfill calls are fire-and-forget on
a cache that is already cold.

### Concurrent same-score ZADD

ZSET members are unique by string value (the serialized JSON), not by score.
Two messages in the same millisecond produce different JSON (different UUIDs,
different body content), so both are stored correctly. No collision risk.

### EVALSHA fallback

`ReactiveRedisTemplate.execute(RedisScript, keys, args)` delegates to Spring
Data Redis' `ScriptExecutor`, which calls `EVALSHA` when the script is already
cached and falls back to `EVAL` transparently when the SHA is missing. No
application-level retry needed.

### Atomicity

With Lua, the race between ZADD and ZREMRANGEBYRANK is completely eliminated --
the script executes as one atomic Redis operation. Two concurrent writers each
get their own script execution, each of which atomically adds-then-trims.

## Validation

```bash
./gradlew :chat-service:compileJava  # compiles with new imports and DefaultRedisScript
./gradlew :chat-service:test         # existing RedisMessageCacheTest passes
```

The existing test suite already covers the key behaviors that the Lua rewrite
must preserve:

| Test | What it validates |
|------|-------------------|
| `hotWriteRoundTrips` | ZADD + deserialize round-trip |
| `readOrdersNewestFirst` | ZSET score ordering survives rewrite |
| `retentionCapsAtHundred` | Trim logic: 150 writes yields 100 newest |
| `cursorReturnsOlderMessagesOnly` | ZRANGEBYSCORE with exclusive upper bound |
| `writeAppliesTtl` | EXPIRE is set and bounded by config |
| `keyExpiresAfterTtl` | Key actually expires (functional EXPIRE) |
| `resilientWhenRedisDown` | Graceful degradation unchanged |

## Estimated effort: ~2 hours
