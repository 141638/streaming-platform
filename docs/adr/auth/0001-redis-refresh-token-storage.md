# ADR-0001: Redis-Based Refresh Token Storage with Atomic Lua Rotation

**Date**: 2026-07-06
**Status**: accepted
**Deciders**: streaming-platform team

## Context

The auth-service originally stored refresh tokens in PostgreSQL (`auth.refresh_token` table) using JPA with pessimistic locking (`SELECT ... FOR UPDATE`). Token rotation required 4 database round-trips across 2 transaction boundaries, and the `RefreshTokenMaintenanceService` existed solely to run family-wide revocation in a `REQUIRES_NEW` transaction (so replay-attack revocation would persist even if the outer rotation transaction rolled back).

Redis was hardened in the same release cycle (AOF + RDB persistence, password auth, memory governance — see [common/ADR-0002: Redis as Ephemeral Data Store](../../common/0002-redis-ephemeral-data-store.md)). Refresh tokens are ephemeral credentials with natural TTLs (7 days) — a textbook Redis use case. The existing PostgreSQL implementation was correct but operationally heavy:

- No expiry cleanup cron existed (expired tokens would accumulate indefinitely)
- Pessimistic locking adds latency under concurrent refresh requests
- Two transaction managers (`REQUIRED` + `REQUIRES_NEW`) added complexity for a single business operation

## Decision

**Migrate refresh token storage from PostgreSQL to Redis, using an atomic Lua script to replace pessimistic locking and multi-transaction coordination.**

### Data Model

```
rt:<sha256hash>          → HSET {userId, familyId, expiresAt, revokedAt, createdAt}
rt_family:<familyId>     → SET of token hashes (secondary index for family revocation)
rt_user:<userId>         → SET of token hashes (secondary index for user-level revocation)
```

Redis `EXPIRE` is set on individual tokens and family sets to match the token TTL (7 days). Expired tokens are auto-deleted by Redis — no cleanup cron needed.

### Rotation Algorithm (Lua Script)

The Lua script `rotate_refresh_token.lua` atomically:

1. **Reads** the old token hash → `INVALID` if not found
2. **Detects replay**: if `revokedAt` is set → bulk-revokes every token in `rt_family:<familyId>` → returns `REVOKED_FAMILY`
3. **Checks expiry**: if `expiresAt` ≤ now → returns `EXPIRED`
4. **Rotates**: marks old token revoked, creates new token, updates family and user index sets → returns `OK`

The entire operation is a single Redis `EVALSHA` call. No application-level locks or transactions are needed — Redis executes Lua scripts atomically.

### Components

| Component | Role |
|---|---|
| `RedisConfig.java` | Provides `RedisTemplate<String, String>` with UTF-8 serialization |
| `RefreshTokenRedisService.java` | Infrastructure layer — key management, Lua script loading/execution, index maintenance |
| `RefreshTokenService.java` | Application layer — validates user existence (PostgreSQL), issues access JWTs, delegates rotation to Redis |
| `rotate_refresh_token.lua` | Atomic rotation script loaded from classpath |

### What Was Removed

- `RefreshTokenEntity.java` — JPA entity (replaced by Redis Hash)
- `RefreshTokenRepository.java` — JPA repository (replaced by `RefreshTokenRedisService`)
- `RefreshTokenMaintenanceService.java` — `REQUIRES_NEW` transaction wrapper (replaced by inline Lua script logic)
- `@Transactional` annotations on `RefreshTokenService` — no longer needed

### What Stayed

- `OpaqueTokenGenerator.java` — still generates 48-byte random refresh secrets
- `HashUtils.java` — still SHA-256 hashes plaintext before storage (now returns hex string instead of `byte[]`)
- `AuthController.java` / `AuthService.java` — same interface, no changes
- `Flyway V6 migration` — kept in place (already applied; table can coexist until explicit cleanup)
- User accounts, PBAC policies, roles — all remain in PostgreSQL

## Alternatives Considered

### Alternative 1: Keep PostgreSQL (status quo)
- **Pros**: No Redis dependency for auth-service, ACID guarantees, familiar JPA patterns
- **Cons**: No expiry cleanup (tokens accumulate indefinitely), pessimistic locking adds latency, 4 round-trips per rotation, `REQUIRES_NEW` transaction complexity
- **Why not**: Redis is already running and hardened. The migration eliminates operational toil (cleanup cron, transaction management) with no loss of correctness. Refresh tokens don't need ACID — they're ephemeral credentials where data loss means "user re-logs in," not "business data is gone."

### Alternative 2: Keep PostgreSQL, add Redis as cache layer
- **Pros**: PostgreSQL remains source of truth, Redis speeds up reads
- **Cons**: Dual-write consistency problems (what if Redis write succeeds but PostgreSQL write fails?), increased complexity, still needs cleanup cron
- **Why not**: A cache layer adds complexity without eliminating the underlying problems. If Redis is reliable enough for reads, it's reliable enough to be the primary store for this specific data class (ephemeral credentials). The chat-service already uses Redis as a latency buffer for messages, where PostgreSQL is the system of record — that pattern makes sense for persistent data, not for TTL-governed tokens.

### Alternative 3: Use Redis without Lua script (application-level coordination)
- **Pros**: No Lua to learn or test
- **Cons**: Multiple Redis round-trips between validation and rotation create a race-condition window. `WATCH`/`MULTI`/`EXEC` (Redis transactions) don't support the conditional logic needed for replay detection and family revocation.
- **Why not**: The Lua script is the only way to make the rotation atomic in Redis. Without it, you'd need application-level distributed locking (another Redis dependency, more complexity). The Lua script is 40 lines — it's a direct translation of the existing Java logic.

## Consequences

### Positive
- **No more expiry cleanup**: Redis `EXPIRE` auto-deletes tokens when their TTL elapses. The PostgreSQL table had no cleanup mechanism — expired tokens accumulated indefinitely.
- **Faster rotation**: Single Redis `EVALSHA` (~0.2ms) replaces 4 PostgreSQL round-trips (~2-5ms each under load).
- **Simpler codebase**: 3 Java files deleted (`Entity`, `Repository`, `MaintenanceService`). No `@Transactional` coordination.
- **Auth hot-path is Redis-only**: Login and token refresh don't touch PostgreSQL for token storage (user validation still hits PG for account existence).

### Negative
- **auth-service depends on Redis**: Previously only chat-service had a Redis dependency. auth-service now needs Redis running to function.
- **Lua script is a new skill requirement**: The team needs to understand Lua to debug rotation issues. Mitigated by comprehensive tests and inline comments in the script.

### Risks
- **Redis data loss**: If Redis crashes without AOF sync, all users are logged out. Mitigation: AOF + RDB persistence configured (`--appendonly yes`, `--save 900 1 --save 300 10`). Acceptable for a streaming platform.
- **Lua script bugs**: A bug in the rotation script could allow token reuse or incorrect revocation. Mitigation: the script is a direct translation of well-tested Java logic; all code paths are covered by `RefreshTokenRedisServiceTest`.

## References

- [ADR-0002: Redis as Ephemeral Data Store](../../common/0002-redis-ephemeral-data-store.md) — overall Redis strategy
- [ADR-0001: Structured JSON Logging](../../common/0001-structured-json-logging.md) — logging infrastructure
- [Logging Architecture](../../LOGGING-ARCHITECTURE.md)
- [Implementation Plan](../../IMPLEMENTATION-PLAN.md#phase-6--production-hardening)
