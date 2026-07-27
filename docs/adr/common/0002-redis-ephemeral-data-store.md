# ADR-0002: Redis as Ephemeral Data Store for Auth, Rate Limiting, and Caching

**Date**: 2026-07-06
**Status**: proposed
**Deciders**: streaming-platform team

## Context

The Redis infrastructure was hardened in Phase 6.6 (pinned image, AOF+RDB persistence, password auth, memory governance). Currently, only chat-service uses Redis — as a ZSET-based latency buffer for recent chat messages (cache-aside, see [ADR-0002: Cache-Aside with Redis ZSET](../chat/0002-cache-aside-redis-zset.md)).

The auth-service, gateway-service, stream-service, and notification-service have no Redis dependency. Several planned Phase 6 features — rate limiting (6.3), idempotency keys (6.1), WebSocket session affinity (6.4) — would benefit from Redis as an ephemeral, low-latency data store. Additionally, the current refresh token implementation stores tokens in PostgreSQL, which is suboptimal for ephemeral credentials with natural TTLs.

This ADR catalogs the Redis use cases we intend to implement, their design approach, and their priority order.

**Constraints:**
- Redis is already deployed (Docker Compose, password-protected, AOF+RDB, 256MB maxmemory with allkeys-lru)
- chat-service is the only current Redis consumer
- All services are Spring Boot 3.3.6 with Spring Cloud Netflix Eureka
- No rate limiting, idempotency, or WebSocket infrastructure exists yet
- Refresh tokens currently use JPA + PostgreSQL with pessimistic locking and token family rotation

## Decision

**Adopt Redis as the primary store for ephemeral, TTL-governed data across all services.** The following use cases are accepted for implementation (when the associated Phase 6 work items are scheduled):

| # | Use Case | Service(s) | Data Pattern | Key Schema | TTL |
|---|---|---|---|---|---|
| 1 | Refresh tokens | auth-service | Hash (HSET) + Set (family index) + Set (user index) | `rt:<sha256hash>`, `rt_family:<familyId>`, `rt_user:<userId>` | 7 days |
| 2 | Rate limiting | gateway-service | String (INCR + EXPIRE) | `rl:<endpoint>:<userIdOrIp>:<window>` | 60s–3600s |
| 3 | Idempotency keys | gateway-service | String (SET NX + EX) | `idem:<key>` | 24 hours |
| 4 | Feature flags | all services | String (GET) or Hash | `ff:<flagName>` | ∞ (no TTL) |
| 5 | WebSocket session affinity | chat-service | Pub/Sub channel + Hash | `ws:session:<sessionId>`, `chat:room:<roomId>` (pub/sub) | connection heartbeat |
| 6 | Distributed locking | any | String (SET NX PX) | `lock:<resource>` | task timeout |
| 7 | Kafka event dedup | notification-service, chat-service (future) | String (SET NX EX) | `dedup:{topic}:{consumerGroupId}:{eventId}` per [ADR-0003](./0003-cross-service-event-dedup-key-scoping.md) | 24 hours |

### Priority

1. **Refresh tokens** — Highest impact. Eliminates a PostgreSQL table, removes the need for an expiry-cleanup cron job, and speeds up the auth hot-path (login + token refresh). Token family rotation with replay-attack detection maps cleanly to Lua scripts. The auth hot-path becomes Redis-only; PostgreSQL remains for user accounts and PBAC policies.

2. **Rate limiting** — Gateway-level protection. `INCR + EXPIRE` pattern is well-established in Redis. Protects login, token refresh, and stream creation endpoints from brute force. No Lua scripts needed for basic counters.

3. **Idempotency keys** — Safe retries for all POST/PUT endpoints. `SET NX + EX` pattern. Enables the auth interceptor's retry-on-401 logic without duplicate side effects.

4. **WebSocket session affinity + Pub/Sub** — Required when chat-service scales beyond 1 instance. Redis Pub/Sub fans out chat messages to all instances; each instance forwards to its connected WebSocket clients. Session affinity maps sessionId → instance.

5. **Feature flags** — Decouple deployment from release. Low volume, simple GET pattern. No TTL needed.

6. **Distributed locking** — For future scheduled tasks (e.g., periodic cleanup, leader election). `SET NX PX` with Redlock for high-availability deployments.

7. **Kafka event dedup** — Already implemented in notification-service (`StreamControlListener`). Key format standardized to `dedup:{topic}:{consumerGroupId}:{eventId}` per [ADR-0003](./0003-cross-service-event-dedup-key-scoping.md). Current implementation uses unscoped key — safe until a second service adds Redis SETNX for the same topic; refactoring tracked in ADR-0003.

## Alternatives Considered

### Alternative 1: Keep Everything in PostgreSQL
- **Pros**: Single data store, no new technology, ACID guarantees
- **Cons**: Refresh tokens don't benefit from relational features (no JOINs, no FK lookups beyond user revoke). TTL cleanup requires a cron job. Rate limiting on PostgreSQL would be very expensive (write per request). PostgreSQL is not designed for pub/sub or distributed locking.
- **Why not**: PostgreSQL is the right tool for persistent relational data — user accounts, PBAC policies, stream metadata, chat message history. It is the wrong tool for ephemeral, high-throughput, TTL-governed data. Using the right store for each data class is a well-established pattern.

### Alternative 2: Use Kafka for Everything Non-DB
- **Pros**: Already in the stack (stream-service, notification-service). Handles pub/sub natively.
- **Cons**: Kafka is a streaming platform, not a key-value store. Rate limiting, idempotency keys, and refresh tokens require point lookups (O(1) by key). Kafka is designed for sequential reads, not point lookups. Distributed locking is not a Kafka use case.
- **Why not**: Kafka and Redis serve complementary roles. Kafka handles async event streaming (stream events, chat messages, notifications). Redis handles synchronous, low-latency point operations (auth, rate limits, caching).

### Alternative 3: Use Only chat-service Redis; Others Stay on PostgreSQL
- **Pros**: Minimal changes, no new Redis dependencies in other services
- **Cons**: Misses the highest-value use case (refresh tokens). auth-service already has a PostgreSQL dependency for refresh tokens that is an awkward fit. Rate limiting without Redis requires an alternative (e.g., Bucket4j in-memory, which doesn't work across instances).
- **Why not**: Redis is already running, hardened, and working. Adding a dependency to auth-service and gateway-service is incremental cost for significant gain.

## Consequences

### Positive
- **Refresh tokens**: No PostgreSQL table, no cron cleanup, faster auth operations. Lua scripts provide equivalent atomicity to the current pessimistic locking approach.
- **Rate limiting**: Simple, battle-tested pattern. Gateway protects all upstream services with no per-service changes.
- **Idempotency**: Gateway-level deduplication means downstream services don't need idempotency logic.
- **Operational simplicity**: One Redis instance serves all ephemeral-data needs. No new infrastructure per use case.

### Negative
- **auth-service gets a Redis dependency**: Currently only chat-service depends on Redis. auth-service will need `spring-boot-starter-data-redis-reactive` + configuration.
- **Lua script complexity**: Token family rotation with replay detection requires a Lua script. The script must be tested as thoroughly as the current Java code.
- **Refresh token data loss risk**: If Redis loses data (crash without AOF sync), all users are logged out. Mitigated by AOF + RDB persistence (already configured). Acceptable trade-off for a streaming platform (not a bank).

### Risks
- **Redis memory pressure**: Multiple use cases compete for the same 256MB maxmemory. Mitigation: allkeys-lru evicts least-recently-used keys. Monitor memory usage when adding use cases. Increase maxmemory if needed.
- **Lua script bugs**: A bug in the refresh token Lua script could allow token reuse or incorrect revocation. Mitigation: comprehensive test coverage; the Lua script is a direct translation of the well-tested Java code.
- **Single Redis instance**: Currently a single Redis container. For production, consider Redis Sentinel or Cluster. Mitigation: AOF + RDB persistence covers data recovery; a cold restart is acceptable for all use cases listed here.

## Implementation Notes

### Refresh Token Lua Script Design

The current JPA implementation uses:
- `lockByTokenHash()` — pessimistic `SELECT FOR UPDATE` to prevent double-refresh races
- `revokeActiveByFamily()` — bulk-revoke all active tokens in a family (replay attack defense)
- `revokeAllActiveByUser()` — bulk-revoke per user (password reset defense)

The Redis Lua script translates these to:
1. `HGETALL rt:<oldHash>` — read old token
2. Validate: exists, not revoked, not expired
3. If revoked → `SMEMBERS rt_family:<familyId>` → `HSET each revoked=true` (replay defense)
4. If valid → `HSET old revoked=true`, `HSET rt:<newHash> ...metadata...`, `EXPIRE rt:<newHash> <ttl>`, `SADD rt_family:<familyId> <newHash>`, `SREM rt_family:<familyId> <oldHash>`, `SADD rt_user:<userId> <newHash>`

### Rate Limiting Design (Implemented: Sliding Window Log)

**Implemented approach (Phase 6.3, 2026-07-28): Sliding Window Log** — each client IP owns a Redis sorted set of epoch-millisecond request timestamps. An atomic Lua script prunes expired entries (`ZREMRANGEBYSCORE`), counts the remainder (`ZCARD`), rejects if at or above the limit, otherwise records the request (`ZADD` with a nonce-suffixed member for millisecond-level uniqueness). Single round-trip, exact to the boundary.

```
Key: rl:<ip> (Sorted Set)
Lua: ZREMRANGEBYSCORE → ZCARD → if count ≥ limit: reject → else: ZADD(nowMs, nowMs:nonce) → EXPIRE
Response: 429 + Retry-After + structured JSON body {"error":"...","error_code":"rate_limit_exceeded","message":"..."}
Config: streaming.gateway.ratelimit.limit=200, .window-seconds=60 (env-var overridable)
```

**Simpler alternative (not yet implemented): Fixed-Window Counter** — per-endpoint, per-identifier, per window:
```
rl:auth:login:<userId>  →  INCR → check ≤ 5  →  EXPIRE 60
rl:api:global:<ip>      →  INCR → check ≤ 100 →  EXPIRE 1
```

The sliding-window-log approach was chosen for the gateway because it has no boundary blind spot (fixed-window allows 2× the limit across a window boundary). The fixed-window `INCR + EXPIRE` pattern remains a valid simplification for lower-throughput endpoints like login rate limiting (ADR auth/0003).

## References

- [ADR-0001: Structured JSON Logging](0001-structured-json-logging.md)
- [ADR-0002: Cache-Aside with Redis ZSET](../chat/0002-cache-aside-redis-zset.md)
- [Logging Architecture](../../LOGGING-ARCHITECTURE.md)
- [Implementation Plan](../../IMPLEMENTATION-PLAN.md#phase-6--production-hardening)
- [Redis persistence documentation](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Redis Lua scripting](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/)
