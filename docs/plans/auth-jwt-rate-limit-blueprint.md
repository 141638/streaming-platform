# Blueprint: Username in JWT + Login Rate Limiting

**Date**: 2026-08-06
**Status**: draft
**Deciders**: hieuht, Claude

## Summary

The JWT `attr.username` claim and the frontend `TokenAttrDto` are already wired -- the V9 Flyway migration seeded `catalog_subject_attribute`, `SubjectAttributeResolver` resolves `username` at line 55, and the frontend `AuthService` parses it at line 103. Two pieces remain: collapse the login 404/401 enumeration oracle into a uniform 401, and add a Redis-Lua sliding-window rate limiter on `POST /v1/login` with dual keying (IP + username) and configurable thresholds.

## Learning Objectives

| Skill | Where you'll implement it |
|-------|---------------------------|
| **Redis Lua scripting (blocking)** | `login_rate_limit.lua` -- INCR + TTL pattern on `RedisTemplate<String,String>` |
| **Rate limiting: dual-key strategy (IP + username)** | Two Redis keys: `login_attempts:ip:<ip>` and `login_attempts:user:<username>` |
| **Configuration properties pattern** | `@ConfigurationProperties("streaming.auth.login-rate-limit")` record |
| **429 with Retry-After** | `TooManyLoginAttemptsException` + `@ExceptionHandler` in `GlobalExceptionHandler` |
| **X-Forwarded-For IP resolution in Servlet** | `HttpServletRequest.getHeader("X-Forwarded-For")` with leftmost extraction |
| **Fail-open vs fail-closed tradeoff** | try/catch in `incrementAndGet()` returning 0 on Redis errors |

## Patterns to Mirror

| Category | Source file | Pattern to copy |
|----------|-------------|-----------------|
| Redis Lua execution (blocking) | `auth-service/.../infrastructure/redis/RefreshTokenRedisService.java:28-43` | `ClassPathResource` → `DefaultRedisScript` → `RedisTemplate.execute(script, keys, args...)` |
| Lua atomic guard pattern | `auth-service/src/main/resources/redis/rotate_refresh_token.lua` | Multiple KEYS + ARGV, return string status, Java enum mapping |
| Gateway Lua sliding-window | `gateway-service/src/main/resources/redis/rate_limit.lua` | `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `EXPIRE` (adapt to counter-based for auth) |
| `@ConfigurationProperties` record | `gateway-service/.../config/RateLimitProperties.java` | Java record with `@DefaultValue`, derived helpers |
| 429 structured response | `gateway-service/.../filter/RateLimitFilter.java:140-161` | JSON body `{"error":"...","error_code":"rate_limit_exceeded","message":"..."}` + `Retry-After` header |
| IP resolution from X-Forwarded-For | `gateway-service/.../filter/RateLimitFilter.java:121-130` | Parse leftmost IP, fallback to `request.getRemoteAddr()` |
| `@ExceptionHandler` in GlobalExceptionHandler | `auth-service/.../api/GlobalExceptionHandler.java` | Add handler for new exceptions returning structured JSON |
| `InvalidCredentialsException` (401 pattern) | `auth-service/.../exception/InvalidCredentialsException.java` | `@ResponseStatus(HttpStatus.UNAUTHORIZED)`, generic message |
| Flyway migration convention | `auth-service/src/main/resources/db/migration/V9__*.sql` | Schema-qualified names (already applied for username) |

## Current State Audit

### Already implemented (ADR-0002 JWT claim -- no code changes needed):
- **V9 migration** (`V9__seed_username_subject_attribute.sql`): Inserts `('username', 'STRING', '...')` into `auth.catalog_subject_attribute`
- **`SubjectAttributeResolver.java:27,55`**: `KEY_USERNAME` constant + `resolveUsername(user)` returning `user.getUsername()`
- **`SubjectAttributes.java:15`**: `@JsonProperty("username") String username` field
- **`JwtAttr.java:23-29`** (pbac-common): `public static String username(Jwt jwt)` helper
- **`AccessTokenIssuanceService.java:93`**: `subjectAttributeResolver.resolveForUser(user, roleSlugs)` -- catalog-driven iteration already includes `username`
- **Frontend `TokenAttrDto`**: `readonly username: string | null` field
- **Frontend `AuthService`**: `parseUsername()` + `_username` signal + `myUsername` public signal

### Not yet implemented:
| Piece | Status | ADR ref |
|-------|--------|---------|
| Uniform 401: collapse 404/401 split | **NOT BUILT** | ADR-0002, Decision 2 |
| Login rate limiter (Lua + service + exception + config) | **NOT BUILT** | ADR-0003 |
| Rate limit key reset on successful login | **NOT BUILT** | ADR-0003 |
| `GlobalExceptionHandler` entries for 429 + structured 401 | **NOT BUILT** | ADR-0003 |
| Fail-open decision implemented | **NOT BUILT** | ADR-0003 |

## Architecture Diagram

```
Client (browser / mobile / script)
   │
   ▼
POST /api/auth/v1/login  { username, password }
   │
   ▼
┌────────────────────────────────────────────────────────┐
│  AuthController.login()                                │
│    │                                                   │
│    ▼                                                   │
│  LoginRateLimitService.check(username, request)  [NEW] │
│    │                                                   │
│    ├─► Resolve client IP (X-Forwarded-For → leftmost)  │
│    │                                                   │
│    ├─► Redis Lua: login_attempts:ip:<clientIp>         │
│    │   ├─ INCR key                                     │
│    │   ├─ EXPIRE key <windowSeconds> (on first hit)    │
│    │   └─ if count > ipLimit → throw 429               │
│    │                                                   │
│    ├─► Redis Lua: login_attempts:user:<username>       │
│    │   ├─ INCR key                                     │
│    │   ├─ EXPIRE key <windowSeconds> (on first hit)    │
│    │   └─ if count > userLimit → throw 429             │
│    │                                                   │
│    ▼ (both counters allow)                             │
│  AuthService.authenticate(request)                     │
│    │                                                   │
│    ├─► findByUsernameAndDeleteFlagFalse(username)      │
│    │   .orElseThrow(→ InvalidCredentialsException)     │ ◄── [FIX] Uniform 401 (was 404)
│    │                                                   │
│    ├─► passwordEncoder.matches(password, hash)         │
│    │   if false → InvalidCredentialsException          │ ◄── Uniform 401 (already)
│    │                                                   │
│    ▼ (success)                                         │
│  LoginRateLimitService.resetUserCounter(username) [NEW]│ ◄── RESET on success
│    │                                                   │
│    ▼                                                   │
│  RefreshTokenService.issueNewFamilySession(userId)     │
│    │  └─► AccessTokenIssuanceService.issueForUser()    │
│    │       └─► JWT { attr: { username, ... } }         │ ◄── ALREADY IN attr
│    │                                                   │
│    ▼                                                   │
│  200 { accessToken, refreshToken, ... }                │
│                                                        │
│  ── OR ──                                              │
│                                                        │
│  429 { "error":"Too Many Requests",                    │
│         "error_code":"rate_limit_exceeded",            │
│         "message":"..." }                              │
│       Retry-After: <windowSeconds>                     │
│                                                        │
│  ── OR ──                                              │
│                                                        │
│  401 { "error":"Unauthorized",                         │
│         "error_code":"invalid_credentials",            │
│         "message":"Invalid user credentials" }         │
└────────────────────────────────────────────────────────┘
```

### Redis Key Layout

```
login_attempts:ip:192.168.0.5       → INTEGER counter, TTL = windowSeconds
login_attempts:user:johndoe         → INTEGER counter, TTL = windowSeconds
```

Simple `STRING` counters with `INCR` + conditional `EXPIRE` (set only on first hit). No sorted sets -- a fixed-window counter with TTL is sufficient for login-endpoint rate limiting, unlike the gateway's per-IP sliding-window-log which needs per-request precision.

### Why a Lua script?

The INCR + EXPIRE must be atomic. Without Lua:
```java
Long count = redis.opsForValue().increment(key);  // step 1
if (count == 1) redis.expire(key, ttl);            // step 2 -- NOT atomic
```
A crash between steps 1 and 2 leaves an eternal key. Lua eliminates this gap.

## Files to Change

### New Files

| File | Purpose |
|------|---------|
| `main/source/backend/auth-service/src/main/resources/redis/login_rate_limit.lua` | Atomic INCR + conditional EXPIRE |
| `main/source/backend/auth-service/src/main/java/com/streaming/auth/config/LoginRateLimitProperties.java` | `@ConfigurationProperties("streaming.auth.login-rate-limit")` record |
| `main/source/backend/auth-service/src/main/java/com/streaming/auth/exception/TooManyLoginAttemptsException.java` | 429 exception with `retryAfterSeconds` |
| `main/source/backend/auth-service/src/main/java/com/streaming/auth/infrastructure/redis/LoginRateLimitService.java` | Lua execution, IP resolution, counter reset |

### Modified Files

| File | Change | Why |
|------|--------|-----|
| `auth-service/.../service/AuthService.java:22` | `new UserAccountNotFoundException(...)` → `InvalidCredentialsException::new` | Uniform 401 -- no username enumeration |
| `auth-service/.../api/AuthController.java` | Inject `LoginRateLimitService` + `HttpServletRequest`; call `rateLimitService.check()` before auth; call `rateLimitService.resetUserCounter()` on success | Rate limit guard |
| `auth-service/.../api/GlobalExceptionHandler.java` | Add handlers for `TooManyLoginAttemptsException` (429) and `InvalidCredentialsException` (structured 401) | Consistent error responses |
| `auth-service/src/main/resources/application.yml` | Add `streaming.auth.login-rate-limit` block | Configuration-driven thresholds |

### No-Change Files (Verified)

| File | Reason unchanged |
|------|-----------------|
| `SubjectAttributeResolver.java` | Already resolves `username` via `KEY_USERNAME` case at line 55 |
| `SubjectAttributes.java` | Already has `username` field at line 15 |
| `StreamingAccessTokenPayload.java` | Already includes `SubjectAttributes` at line 28 |
| `JwtAttr.java` (pbac-common) | Already has `username()` static helper at line 23 |
| `TokenAttrDto.ts` (frontend) | Already has `username` field at line 23 |
| `AuthService.ts` (frontend) | Already parses and signals `username` from JWT at line 103 |
| `auth.interceptor.ts` | Token attachment unchanged |
| `V9__seed_username_subject_attribute.sql` | Already applied |

## Tasks

### Task 1: Uniform 401 -- Collapse the Login Enumeration Oracle

- **Action**: In `AuthService.authenticate()`, change the empty-Optional path from `UserAccountNotFoundException` (404) to `InvalidCredentialsException` (401). Keep `UserAccountNotFoundException` alive for internal UUID-based lookups (`AccessTokenIssuanceService.issueForUser()` at line 55 -- that call uses `findByIdAndDeleteFlagFalse`, not `findByUsername`, so it is unaffected).
- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/service/AuthService.java` (line 22)

**Before** (current at line 21-23):
```java
final UserAccountEntity requestUser = this.userAccountRepository
        .findByUsernameAndDeleteFlagFalse(loginRequest.username())
        .orElseThrow(() -> new UserAccountNotFoundException(loginRequest.username()));
```

**After**:
```java
final UserAccountEntity requestUser = this.userAccountRepository
        .findByUsernameAndDeleteFlagFalse(loginRequest.username())
        .orElseThrow(InvalidCredentialsException::new);
```

- **Validate**: Both unknown-user and bad-password login attempts return 401 with identical response bodies.

### Task 2: Create `login_rate_limit.lua`

- **Files**: `main/source/backend/auth-service/src/main/resources/redis/login_rate_limit.lua`

```lua
-- Atomic login-attempt counter with TTL.
--
-- INCR the key and set EXPIRE only on the first hit (count == 1).
-- The caller compares the returned count against the configured limit.
--
-- KEYS:
--   KEYS[1]  login_attempts:ip:<clientIp>   -- or login_attempts:user:<username>
--
-- ARGV:
--   ARGV[1]  ttl-seconds                     -- window size in seconds
--
-- RETURN:
--   Integer count -- the number of attempts recorded for this key
--                    AFTER incrementing (1-based).

local key     = KEYS[1]
local ttlSecs = tonumber(ARGV[1])

local count = redis.call('INCR', key)

-- Only set TTL on the first increment to avoid
-- extending the window on every request.
if count == 1 then
    redis.call('EXPIRE', key, ttlSecs)
end

return count
```

- **Validate**: First call returns 1 + sets TTL; second call returns 2, TTL unchanged; after expiry, key is gone and next call returns 1.

### Task 3: Create `LoginRateLimitProperties.java`

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/config/LoginRateLimitProperties.java`

```java
package com.streaming.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "streaming.auth.login-rate-limit")
public record LoginRateLimitProperties(
        @DefaultValue("30")  int ipMaxAttempts,
        @DefaultValue("10")  int userMaxAttempts,
        @DefaultValue("300") int windowSeconds   // 5 minutes
) {
}
```

- **Add to `application.yml`**:
```yaml
streaming:
  auth:
    login-rate-limit:
      ip-max-attempts: ${LOGIN_RATE_LIMIT_IP_MAX:30}
      user-max-attempts: ${LOGIN_RATE_LIMIT_USER_MAX:10}
      window-seconds: ${LOGIN_RATE_LIMIT_WINDOW_SECONDS:300}
```

### Task 4: Create `TooManyLoginAttemptsException.java`

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/exception/TooManyLoginAttemptsException.java`

```java
package com.streaming.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(String scope, long retryAfterSeconds) {
        super("Too many login attempts (" + scope + "). Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

### Task 5: Create `LoginRateLimitService.java`

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/infrastructure/redis/LoginRateLimitService.java`

Key design decisions:
- **Fail-open**: Redis errors caught + logged, returns count 0 (allows login)
- **Case normalization**: Username keys lowercased to prevent case-variant bypass
- **Lua script loaded once** in constructor via `ClassPathResource` -- matches `RefreshTokenRedisService` pattern

```java
package com.streaming.auth.infrastructure.redis;

import com.streaming.auth.config.LoginRateLimitProperties;
import com.streaming.auth.exception.TooManyLoginAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Component
public class LoginRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitService.class);
    private static final String IP_KEY_PREFIX   = "login_attempts:ip:";
    private static final String USER_KEY_PREFIX = "login_attempts:user:";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final RedisTemplate<String, String> redis;
    private final LoginRateLimitProperties properties;
    private final RedisScript<Long> script;

    public LoginRateLimitService(RedisTemplate<String, String> redisTemplate,
                                  LoginRateLimitProperties properties) throws IOException {
        this.redis = redisTemplate;
        this.properties = properties;
        String lua = new ClassPathResource("redis/login_rate_limit.lua")
                .getContentAsString(Objects.requireNonNull(StandardCharsets.UTF_8));
        this.script = new DefaultRedisScript<>(lua, Long.class);
    }

    public void check(String username, HttpServletRequest request) {
        String ip = resolveClientIp(request);
        String normalizedUsername = username.toLowerCase();

        long ipCount   = incrementAndGet(IP_KEY_PREFIX + ip);
        long userCount = incrementAndGet(USER_KEY_PREFIX + normalizedUsername);

        if (ipCount > properties.ipMaxAttempts()) {
            log.warn("Login rate limit exceeded (IP): ip={} count={} limit={}",
                    ip, ipCount, properties.ipMaxAttempts());
            throw new TooManyLoginAttemptsException("ip", properties.windowSeconds());
        }
        if (userCount > properties.userMaxAttempts()) {
            log.warn("Login rate limit exceeded (user): username={} count={} limit={}",
                    normalizedUsername, userCount, properties.userMaxAttempts());
            throw new TooManyLoginAttemptsException("user", properties.windowSeconds());
        }
    }

    public void resetUserCounter(String username) {
        try {
            redis.delete(USER_KEY_PREFIX + username.toLowerCase());
        } catch (Exception e) {
            log.warn("Failed to reset login rate counter for user={}", username, e);
        }
    }

    private long incrementAndGet(String key) {
        try {
            Long result = redis.execute(script,
                    List.of(key),
                    String.valueOf(properties.windowSeconds()));
            return result != null ? result : 0L;
        } catch (Exception e) {
            log.warn("Login rate-limit Redis error key={}, failing open", key, e);
            return 0L; // fail-open
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
```

### Task 6: Modify `AuthController.java`

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/api/AuthController.java`

Inject `LoginRateLimitService` and `HttpServletRequest`. Call `rateLimitService.check()` before `authService.authenticate()`. Call `rateLimitService.resetUserCounter()` on success (do NOT reset on failure).

### Task 7: Update `GlobalExceptionHandler.java`

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/api/GlobalExceptionHandler.java`

Add two handlers:
1. `TooManyLoginAttemptsException` → 429 + `Retry-After` header + structured JSON body
2. `InvalidCredentialsException` → 401 + structured JSON body `{"error":"Unauthorized","error_code":"invalid_credentials","message":"Invalid user credentials"}`

Both handlers use the same JSON shape convention as the gateway's error responses.

### Task 8: Register configuration binding

- **Files**: `main/source/backend/auth-service/src/main/java/com/streaming/auth/AuthApplication.java`

Verify `@EnableConfigurationProperties` or `@ConfigurationPropertiesScan` covers `LoginRateLimitProperties`. Add explicit binding if needed.

### Task 9: Add config to `application.yml`

- **Files**: `main/source/backend/auth-service/src/main/resources/application.yml`

Add under the existing `streaming:` block:
```yaml
streaming:
  auth:
    login-rate-limit:
      ip-max-attempts: ${LOGIN_RATE_LIMIT_IP_MAX:30}
      user-max-attempts: ${LOGIN_RATE_LIMIT_USER_MAX:10}
      window-seconds: ${LOGIN_RATE_LIMIT_WINDOW_SECONDS:300}
```

### Task 10: Unit and Integration Tests

New test file: `main/source/backend/auth-service/src/test/java/com/streaming/auth/infrastructure/redis/LoginRateLimitServiceTest.java`

Test scenarios:
| Test | What it verifies |
|------|-----------------|
| `check_underLimit_allows` | counts within limits → no exception |
| `check_ipExceeded_throws429` | IP count > `ipMaxAttempts` → `TooManyLoginAttemptsException` |
| `check_userExceeded_throws429` | user count > `userMaxAttempts` → `TooManyLoginAttemptsException` |
| `check_redisError_failsOpen` | Redis throws → count returned 0, no exception |
| `resetUserCounter_deletesCorrectKey` | `redis.delete("login_attempts:user:lowercase")` called |
| `resetUserCounter_redisError_swallows` | Redis throws during delete → no exception propagated |
| `resolveIp_prefersXForwardedFor` | `"1.2.3.4, 5.6.7.8"` → extracts `"1.2.3.4"` |
| `resolveIp_fallsBackToRemoteAddr` | No `X-Forwarded-For` → uses `request.getRemoteAddr()` |
| `username_key_is_lowercased` | `"TestUser"` → key is `login_attempts:user:testuser` |

Modify existing tests:
- `AuthServiceTest`: Add assertions that both unknown-user and wrong-password throw `InvalidCredentialsException` (not `UserAccountNotFoundException`)
- `AuthControllerTest`: Add mock `LoginRateLimitService`, verify `check()` called before auth, `resetUserCounter()` called on success, NOT called on failure, 429 response format

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| **Redis outage blocks all logins** | Low (Redis persisted via AOF+RDB) | **Fail-open**: `incrementAndGet()` catches exceptions, returns 0, logged at WARN |
| **NAT/shared-IP false positives** | Medium | IP limit is generous (30/5 min). Username counter is the precise brake (10/5 min). Configurable per environment via env vars |
| **Lockout-as-DoS via username counter** | Low-Medium | Short window (5 min), per-IP counter catches distributed attacks. On success, username counter resets immediately |
| **X-Forwarded-For spoofing** | Low | Only the gateway sets it; direct connections to auth bypass gateway but see socket IP |
| **`UserAccountNotFoundException` removal breaks internal callers** | Low | Only `findByUsername` call is in `AuthService.authenticate()`. `AccessTokenIssuanceService` and `RefreshTokenService` use UUID-based lookups. Exception class kept for those |
| **Username case sensitivity** | Low | Keys are lowercased in both `check()` and `resetUserCounter()`. Prevents `"Admin"` vs `"admin"` creating two counters |
| **TTL extension on repeated failures** | None | Lua sets EXPIRE only on `count==1`. Window boundary is fixed from first attempt |

## Validation

```bash
# 1. Build
cd main/source/backend/auth-service
./gradlew compileJava

# 2. Run tests
./gradlew test --tests "*LoginRateLimit*"
./gradlew test --tests "*AuthService*"
./gradlew test --tests "*AuthController*"

# 3. Uniform 401 -- unknown username (must NOT return 404)
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/auth/v1/login \
  -H "Content-Type: application/json" \
  -d '{"username":"nonexistent_user_abc","password":"x"}'
# Expected: 401

# 4. Uniform 401 -- wrong password (must match unknown-user response body)
curl -s -X POST http://localhost:8080/api/auth/v1/login \
  -H "Content-Type: application/json" \
  -d '{"username":"real_user","password":"wrong"}'
# Expected: 401, body identical to step 3

# 5. Rate limit -- exceed IP limit
for i in $(seq 1 31); do
  echo -n "$i: "
  curl -s -o /dev/null -w "%{http_code}" \
    -X POST http://localhost:8080/api/auth/v1/login \
    -H "Content-Type: application/json" \
    -H "X-Forwarded-For: 10.99.99.99" \
    -d "{\"username\":\"user_$i\",\"password\":\"x\"}"
  echo
done
# Expected: first 30 → 401, 31st → 429

# 6. Rate limit -- check Retry-After header on 429
curl -s -I -X POST http://localhost:8080/api/auth/v1/login \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 10.99.99.99" \
  -d '{"username":"blocked","password":"x"}'
# Expected: HTTP/1.1 429, Retry-After: 300

# 7. Username counter resets on success
redis-cli GET "login_attempts:user:valid_user"
# ... then login with correct password ...
redis-cli GET "login_attempts:user:valid_user"
# Expected: (nil)

# 8. Fail-open: stop Redis, login should still work
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/auth/v1/login \
  -H "Content-Type: application/json" \
  -d '{"username":"any","password":"x"}'
# Expected: 401 (NOT 429, NOT 500)

# 9. Verify JWT contains username in attr claim
echo "<token>" | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool | grep -A5 '"attr"'
# Expected: { "roles": [...], "tier": "...", "verified_streamer": ..., "username": "..." }

# 10. Frontend already parses username from JWT -- verify in browser console:
# > JSON.parse(atob(localStorage.getItem('streaming_access_token').split('.')[1])).attr.username
# Expected: "your_username"
```

---

**Key insight from the audit**: The V9 Flyway migration, `SubjectAttributeResolver`, `SubjectAttributes`, `JwtAttr`, and frontend `AuthService` already have the `username` claim fully wired end-to-end. ADR-0002's JWT claim work requires zero code changes. The actual implementation work is:

1. **1 line change** in `AuthService.java` (uniform 401)
2. **4 new files** (Lua script, properties, exception, rate limit service)
3. **2 modified files** (controller, exception handler)
4. **1 config block** in `application.yml`
5. **Tests** for all of the above
