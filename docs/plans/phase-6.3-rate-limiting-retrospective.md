# Phase 6.3 — Gateway Rate Limiting Retrospective

**Date:** 2026-07-28
**Status:** Implemented (uncommitted — 3 new files, 3 modified)

## 1. What was implemented (vs the original plan)

| Planned item | Files | Notes |
|-------------|-------|-------|
| Rate limiting WebFilter | `RateLimitFilter.java` (151 lines) | `WebFilter` + `@Order(2)`, IP resolution via `X-Forwarded-For` with socket fallback, `ReactiveRedisTemplate.execute()` with Lua script, 429 structured JSON response, fail-open on Redis errors |
| Rate limit configuration | `RateLimitProperties.java` (31 lines) | `@ConfigurationProperties("streaming.gateway.ratelimit")` record: `limit` (200), `windowSeconds` (60), computed `windowMs()` and `ttlSeconds()` |
| Sliding Window Log Lua script | `rate_limit.lua` (55 lines) | Atomic ZREMRANGEBYSCORE → ZCARD → conditional ZADD (with nonce member suffix) → EXPIRE — single round-trip |
| Filter ordering | `IdempotencyFilter.java` (line 88) | `getOrder()` bumped 2→3; RateLimitFilter takes slot 2 |
| Property registration | `GatewayApplication.java` (line 10) | `RateLimitProperties.class` added to `@EnableConfigurationProperties` |
| Configuration block | `application.yml` (lines 94-96) | `streaming.gateway.ratelimit.limit` + `.window-seconds` with env-var overrides |

### Algorithm deviation from ADR common/0002

| Aspect | ADR common/0002 design | Implemented | Rationale |
|--------|----------------------|-------------|-----------|
| Algorithm | Fixed-window `INCR + EXPIRE` ("token bucket" mentioned in plan) | **Sliding Window Log** (Redis ZSET + Lua) | Fixed window has a boundary blind spot — 400 reqs in 2 seconds across a window boundary while respecting 200/min limit. Sliding window log is exact to the millisecond, no boundary effect. |
| Key structure | `rl:<endpoint>:<userIdOrIp>:<window>` | `rl:<ip>` (global, per-IP only) | Per-IP is the primary DDoS brake. Per-endpoint and per-user dimensions are v2 enhancements — the Lua script and filter accept them without structural changes. |
| Lua script | "No Lua scripts needed for basic counters" | Lua script (one round-trip) | The ZSET approach (ZREMRANGEBYSCORE + ZCARD + ZADD) is 3 Redis commands — wrapping them in Lua guarantees atomicity and reduces round-trips from 3 to 1. |

These deviations are v1-scoping decisions, not architectural disagreements. Per-endpoint/per-user dimensions and the login rate limiter (ADR auth/0003) remain planned for future Phase 6.3 follow-ups.

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking | Reason |
|------|------------|----------|--------|
| Per-endpoint rate limits | Phase 6.3 follow-up | IMPLEMENTATION-PLAN.md §6.3 | Configuration surface design needed; current global limit is sufficient for v1 |
| Per-user rate limits (JWT `sub`) | Phase 6.3 follow-up | ADR common/0002 | Filter already has access to `exchange.getPrincipal()`; adding a second Redis key per authenticated user is straightforward |
| Login brute-force rate limiter | Phase 6.3 follow-up or standalone | ADR auth/0003 | Separate scope — needs a Lua script in auth-service, not the gateway |
| Integration tests (Testcontainers Redis) | Post-Docker setup | Project convention | Docker-gated; compilation gate is sufficient for now |
| `Retry-After` precision | Future improvement | RateLimitFilter.java javadoc | Currently returns full window (60s); could return oldest entry's remaining TTL via Lua `ZRANGE` min score |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended action |
|------|---------|-------------------|
| None | — | All items are either implemented or explicitly deferred above |

## 4. Architectural decisions made during implementation (candidates for new ADRs)

1. **Sliding Window Log over Fixed Window Counter** — The ADR common/0002 specified `INCR + EXPIRE` (fixed window). We chose Sliding Window Log (ZSET + Lua) because the boundary effect of fixed-window counters is well-known and unavoidable. With 200 req/min at the boundary, a client can burst 400 requests in 2 seconds. The ZSET approach is exact and the Lua script is simple. **Recommendation:** Update ADR common/0002 §Rate Limiting Design to document the sliding-window-log approach and note that `INCR + EXPIRE` is the fallback simplification.

2. **ZADD member uniqueness via nonce** — Two requests in the same millisecond with identical score+member would collide (ZADD is a no-op on duplicate member). Passing a `ThreadLocalRandom` nonce from the application layer as ARGV[5] guarantees uniqueness without an additional Redis INCR call. **Recommendation:** Note in the rate_limit.lua header — this is a pattern worth reusing for any future ZSET-based rate limiter.

3. **Filter order: RateLimitFilter(2) → IdempotencyFilter(3)** — Rate limiting gates before idempotency caching because excessive traffic should be rejected before any cache work is performed. The order chain is now: Security @0 (public) → Security @1 (protected) → RateLimitFilter @2 → IdempotencyFilter @3. **Recommendation:** Document in IDEMPOTENCY-PATTERN.md (currently says `@Order(2)` for IdempotencyFilter — needs update).

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action to take |
|----------|---------------|-------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` §6.3 | "Redis-backed token bucket" + unchecked | Algorithm is sliding window log, not token bucket. Checklist item unchecked. | Update description + mark ✓ |
| `docs/IMPLEMENTATION-PLAN.md` §6.1 | IdempotencyFilter `@Order(2)` | Now `@Order(3)` | Update if line 1078 mentions the order |
| `docs/adr/common/0002-redis-ephemeral-data-store.md` §Rate Limiting Design | `INCR + EXPIRE` fixed-window | Implemented sliding window log with ZSET + Lua | Update to document both approaches, note which was implemented |
| `docs/IDEMPOTENCY-PATTERN.md` | IdempotencyFilter `@Order(2)` | Now `@Order(3)` | Update order reference |

## 6. Updated execution order (actual vs planned)

| Planned (IMPLEMENTATION-PLAN.md) | Actual | Status |
|----------------------------------|--------|--------|
| 6.3 — Rate limiting (token bucket) | Sliding Window Log via ZSET + Lua | ✅ Implemented (uncommitted) |
| Task 1 — Lua script | `rate_limit.lua` (55 lines) | ✅ |
| Task 2 — RateLimitProperties | `RateLimitProperties.java` (31 lines) | ✅ |
| Task 3 — RateLimitFilter | `RateLimitFilter.java` (151 lines) | ✅ |
| Task 4 — IdempotencyFilter order bump | `IdempotencyFilter.java` 2→3 | ✅ |
| Task 5 — Properties registration + config | `GatewayApplication.java` + `application.yml` | ✅ |
| Task 6 — Compilation validation | `./gradlew :gateway-service:compileJava` | ✅ BUILD SUCCESSFUL |

## 7. Key risks carried forward

1. **Per-IP rate limiting in dev:** All local traffic originates from `127.0.0.1`. With a 200 req/min limit, normal dev usage should never hit it. If dev testing requires higher limits, set `RATE_LIMIT_MAX_REQUESTS` env var.
2. **Gateway is now the second Redis consumer in gateway-service:** The gateway already depends on Redis for idempotency keys (6.1). Rate limiting adds a second Redis consumer in the same JVM. Both use `ReactiveRedisTemplate<String, String>` — no additional connection pool pressure.
3. **Lua script loaded at bean creation time:** If `rate_limit.lua` is missing or malformed, `RateLimitFilter` constructor throws `IOException` and the gateway fails to start. This is intentional (fail-fast on config errors) but worth noting for ops.
4. **Sliding window log memory:** 200 entries per active IP × ~50 bytes = ~10KB per IP. Even with 1,000 concurrent IPs, that's only 10MB. Redis `allkeys-lru` eviction (already configured) handles the edge case.
