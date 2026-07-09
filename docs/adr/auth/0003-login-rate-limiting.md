# ADR-0003: Redis-Lua Login Brute-Force Rate Limiting

**Date**: 2026-07-09
**Status**: proposed
**Deciders**: streaming-platform team

## Context

`POST /v1/login` is a public, unauthenticated endpoint (permitAll in
`SecurityConfig`) with **no throttle of any kind** — a client can submit unlimited
credential guesses at machine speed. There is no rate-limiting filter, interceptor, or
bucket utility anywhere in auth-service or the common module.

This gap matters more now that the channel redesign makes usernames a first-class,
in-app-visible identifier ([auth/ADR-0002](0002-public-username-handle-and-login-hardening.md)).
Even though channel access is authenticated-only (not open to guests), any logged-in user
can read another user's handle, and enumeration on the login path was already possible via
the old 404-vs-401 response split (fixed to a uniform 401 in ADR-0002). Once a valid
username is known, the only remaining barrier is the password — and that barrier is
currently unthrottled.

The service already runs Redis, hardened with persistence and auth
([ADR-0001](0001-redis-refresh-token-storage.md)), and already executes atomic Lua scripts
from the classpath for refresh-token rotation. That gives us a proven, in-house pattern for
an atomic counter without adding a dependency (no Bucket4j, no Redisson).

## Decision

**Add a Redis-Lua fixed/sliding-window rate limiter in front of the login credential check,
keyed on both username and client IP, throwing HTTP 429 when either exceeds its threshold.**

Reuse the exact infrastructure pattern from `RefreshTokenRedisService`:
`RedisTemplate<String,String>` + a classpath Lua script executed via `DefaultRedisScript`,
so the check-and-increment is atomic in a single round-trip.

### Components

| Component | Role |
|---|---|
| `src/main/resources/redis/login_rate_limit.lua` | Atomic counter: `INCR` key, set `EXPIRE` on first hit, return current count |
| `LoginRateLimitService.java` (`infrastructure/redis/`) | Loads/executes the Lua for the username key and the IP key; throws `TooManyLoginAttemptsException` when either exceeds its limit; resets the username counter on successful auth |
| `TooManyLoginAttemptsException.java` (`exception/`) | `@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)` (429) |
| `LoginRateLimitProperties` (`@ConfigurationProperties`) | `auth.login.max-attempts`, `auth.login.window-seconds`, IP-vs-user thresholds — no magic numbers |

### Keys and windows

```
login_attempts:user:<username>   → INCR, EXPIRE = window-seconds
login_attempts:ip:<clientIp>     → INCR, EXPIRE = window-seconds
```

- The check runs in `AuthService.authenticate` (or the controller) **before** the DB lookup
  and password comparison, so guesses are rejected without touching PostgreSQL.
- Client IP comes from the request, honouring the gateway's `X-Forwarded-For` (the app sits
  behind the gateway, so the socket IP would otherwise always be the gateway).
- On **successful** authentication, reset `login_attempts:user:<username>` so a legitimate
  user who mistyped a few times isn't penalised after getting in.

### Anti-DoS posture

A naive hard per-username lockout is itself an attack: an adversary can lock a victim out by
deliberately failing that victim's logins. To mitigate:

- The **IP-scoped** counter is the primary brake (attackers usually share few IPs).
- The **username** counter uses a **higher threshold** and prefers *graduated slowdown / soft
  block within a short window* over a long hard lockout.
- Thresholds/windows are config-driven so they can be tuned without a redeploy of logic.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| No rate limiting (status quo) | Rejected | Leaves `/login` open to unlimited credential guessing; unacceptable once usernames are enumerable/visible. |
| Bucket4j / Resilience4j in-memory | Rejected | Per-instance state doesn't hold across horizontally-scaled auth-service replicas; adds a dependency for what Redis already does. |
| Redisson `RRateLimiter` | Rejected | Heavier Redis client dependency alongside the existing `RedisTemplate`; the Lua pattern is already established here. |
| Spring Cloud Gateway `RequestRateLimiter` at the gateway | Deferred | Coarser (per-route, not per-username); useful as a future outer layer but can't key on the login *username*. May add later as defense-in-depth. |
| Redis-Lua counter (this) | **Chosen** | Atomic, distributed, no new dependency, mirrors the proven refresh-token rotation pattern. |

## Consequences

- **Positive**: `/login` gains real, cluster-wide brute-force protection. Guesses are
  rejected before hitting PostgreSQL. No new dependency — reuses Redis + the Lua execution
  pattern already in the service.
- **Negative**: Redis is now on the login hot path (it already is, for session issuance, so
  no new failure domain). A Redis outage must **fail open or closed** by explicit choice —
  decide in implementation (lean fail-open for availability, with an alert, since login is
  user-facing).
- **Risks**:
  - *Lockout-as-DoS* — mitigated by IP-primary keying + graduated username handling (above).
  - *NAT/shared-IP false positives* — many legit users behind one IP could trip the IP
    counter. Mitigation: set the IP threshold generously; the username counter is the precise
    one.
  - *`X-Forwarded-For` spoofing* — only trust the gateway-set value; do not trust
    client-supplied `X-Forwarded-For` upstream of the gateway.

## References

- Related ADRs: [ADR-0001: Redis-Based Refresh Token Storage](0001-redis-refresh-token-storage.md)
  (the Lua execution pattern reused here); [ADR-0002: Public Username Handle & Login Hardening](0002-public-username-handle-and-login-hardening.md)
  (uniform-401 enumeration fix; this ADR is the throttling half referenced there)
- Source files: `login_rate_limit.lua`, `LoginRateLimitService.java`,
  `TooManyLoginAttemptsException.java`, `AuthService.java`, `SecurityConfig.java`,
  `RefreshTokenRedisService.java` (pattern precedent)
- External docs: [common/ADR-0002: Redis as Ephemeral Data Store](../../common/0002-redis-ephemeral-data-store.md)
