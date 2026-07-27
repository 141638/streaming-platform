# Blueprint: Phase 6.1 — Idempotency Keys

**Status:** Implemented (2026-07-27) — all 5 tasks complete, uncommitted
**Pattern doc:** [IDEMPOTENCY-PATTERN.md](../IDEMPOTENCY-PATTERN.md)

## Summary

Add idempotency-key support to the gateway and frontend so state-changing
requests (POST/PUT/PATCH/DELETE) survive network duplicates, browser retries,
double-clicks, and token-refresh retries. The gateway caches successful
responses in Redis keyed by `Idempotency-Key` header; duplicates return the
cached response without hitting downstream services. Frontend components
generate a fresh key per user action; the auth interceptor retries failed
POSTs with the same key.

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| Gateway filter | `SecurityConfig.java:56-81` — `GlobalFilter` pattern, `@Order` annotation | `IdempotencyFilter` as `@Component GlobalFilter` with `@Order(-1)` (before security) |
| Config props | `JwtGatewayProperties.java` — `@ConfigurationProperties` record with `@DefaultValue` | `IdempotencyProperties` record |
| Redis key design | `notification-service` — `dedup:{scope}:{id}` pattern in Kafka consumer | `idempotent:{uuid}` key namespace |
| Frontend service | `auth.service.ts` — `@Injectable({providedIn:'root'})`, `inject(HttpClient)` | `IdempotencyService` with `newKey()` |
| Interceptor retry | `auth.interceptor.ts:35-41` — SAFE_METHODS guard, single-flight refresh | Enable POST retry when idempotency key present |
| Error resilience | `RedisMessageCache.java` — `.onErrorResume()` pattern | Filter must forward on Redis failure (fail-open) |

## Architecture

```
Client clicks "Create Stream"
          │
          ▼
┌─────────────────────┐
│  IdempotencyService │  → crypto.randomUUID()
│  newKey()           │
└─────────┬───────────┘
          │  key: "a1b2c3d4-..."
          ▼
┌─────────────────────┐
│  StreamService      │  → http.post(url, body, { headers: { 'Idempotency-Key': key } })
│  create(body, key)  │
└─────────┬───────────┘
          │  POST /api/streams/v1/streams
          │  Idempotency-Key: a1b2c3d4-...
          ▼
┌─────────────────────────┐
│  Gateway                │
│  ┌───────────────────┐  │
│  │ IdempotencyFilter │  │
│  │ @Order(-1)        │  │
│  └───────┬───────────┘  │
│          │               │
│    Redis GET idempotent:a1b2c3d4-...
│          │               │
│    ┌─────┴─────┐        │
│    │            │        │
│  HIT          MISS       │
│    │            │        │
│    ▼            ▼        │
│  Return      Forward     │
│  cached      to          │
│  201         stream-     │
│              service     │
│              │           │
│              ▼           │
│         Cache 2xx        │
│         response         │
│         in Redis         │
└──────────────────────────┘

Token expires during POST:
  401 → refresh → retry with SAME key → gateway returns cached 201
```

## Files to Create

| File | Action | Why |
|------|--------|-----|
| `gateway-service/.../filter/IdempotencyFilter.java` | Create | Gateway filter: read header → check Redis → forward or replay cached response |
| `gateway-service/.../config/IdempotencyProperties.java` | Create | Config record: TTL for cached responses |
| `gateway-service/.../filter/CachedResponse.java` | Create | Internal record: serialized response envelope (status, headers, body) |
| `frontend/.../core/services/idempotency.service.ts` | Create | `newKey()` generator, header helper |

## Files to Modify

| File | Action | Why |
|------|--------|-----|
| `gateway-service/build.gradle.kts` | Add Redis dependency | `spring-boot-starter-data-redis-reactive` + `jackson-databind` (for JSON serialization) |
| `gateway-service/.../application.yml` | Add Redis config | `spring.data.redis.*` connection + `streaming.gateway.idempotency.ttl` |
| `gateway-service/.../GatewayApplication.java` | Enable config props | `@EnableConfigurationProperties(IdempotencyProperties.class)` |
| `frontend/.../interceptors/auth.interceptor.ts` | Enable POST retry | Retry POST/PUT/PATCH/DELETE after token refresh when idempotency key present |
| `frontend/.../services/stream.service.ts` | Add idempotency key | All POST/PATCH/PUT/DELETE methods accept and forward `Idempotency-Key` |
| `frontend/.../services/chat.service.ts` | Add idempotency key | `sendMessage()` accepts and forwards `Idempotency-Key` |
| `frontend/.../services/subscription.service.ts` | Add idempotency key | All PUT/PATCH/DELETE methods accept and forward `Idempotency-Key` |
| `frontend/.../services/notification.service.ts` | Add idempotency key | `markAsRead()`, `markAllAsRead()` accept and forward `Idempotency-Key` |
| `frontend/.../services/chat-moderation.service.ts` | Add idempotency key | `ban()`, `updateDuration()`, `unban()` accept and forward `Idempotency-Key` |
| `frontend/.../services/presence.service.ts` | No change needed | `sendHeartbeat()` is a periodic fire-and-forget — idempotency not required |

## Tasks

### Task 1: Gateway Redis Dependency + Config

- **Action**: Add `spring-boot-starter-data-redis-reactive` to gateway `build.gradle.kts`, add Redis connection config to `application.yml`, create `IdempotencyProperties` record
- **Files**:
  - `main/source/backend/gateway-service/build.gradle.kts`
  - `main/source/backend/gateway-service/src/main/resources/application.yml`
  - `main/source/backend/gateway-service/src/main/java/com/streaming/gateway/config/IdempotencyProperties.java`
  - `main/source/backend/gateway-service/src/main/java/com/streaming/gateway/GatewayApplication.java`
- **Validate**: `./gradlew :gateway-service:compileJava`

### Task 2: IdempotencyFilter

- **Action**: Create the `GlobalFilter` that intercepts state-changing requests, checks Redis for cached responses, and caches successful (2xx) responses. Create `CachedResponse` internal record for JSON serialization.
- **Files**:
  - `main/source/backend/gateway-service/src/main/java/com/streaming/gateway/filter/IdempotencyFilter.java`
  - `main/source/backend/gateway-service/src/main/java/com/streaming/gateway/filter/CachedResponse.java`
- **Design decisions**:
  - **Fail-open**: Redis errors → log warning + forward request (never block traffic)
  - **Only cache 2xx**: Errors are not cached — client can retry
  - **Method gate**: GET/HEAD/OPTIONS skip filter (already idempotent per HTTP spec)
  - **TTL**: 24 hours default (`streaming.gateway.idempotency.ttl-seconds`)
  - **Response capture**: `ServerHttpResponseDecorator` with `DataBufferUtils.join` to buffer body
  - **Cache key**: `idempotent:{uuid}`
  - **Filter order**: `@Order(-1)` — runs before security (idempotency check should happen even for auth failures? No — after auth but before routing. Let's use `@Order(0)` since the public/protected chains are at 0/1)
  
  Actually, the filter should run AFTER authentication but BEFORE routing to downstream services. The `@Order` on the security chains is 0 (public) and 1 (protected). A `GlobalFilter` with `@Order(1)` would run after both security chains but before most gateway filters. Let me use `@Order(2)` or just let it default (which is after security).

  Wait — `GlobalFilter` ordering in Spring Cloud Gateway: the default order for auto-configured filters like `NettyRoutingFilter` is around `Integer.MAX_VALUE`. Our filter should run before routing. Let me use `@Order(0)` — actually, we want this to run after security so the request is authenticated first. The security filter chain has its own ordering that's separate from `GlobalFilter`. Security runs first regardless.

  Let me use `@Order(0)` — this ensures it runs early in the gateway filter chain but after security (which uses a different mechanism, `SecurityWebFilterChain`, not `GlobalFilter`).

- **Validate**: `./gradlew :gateway-service:compileJava`

### Task 3: Frontend IdempotencyService

- **Action**: Create the `IdempotencyService` — `newKey()` returns `crypto.randomUUID()`, injectable singleton. Wire idempotency keys into every state-changing service method.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/core/services/idempotency.service.ts`
  - `main/source/frontend/streaming-ui/src/app/core/services/stream.service.ts` (modify POST/PATCH/PUT methods)
  - `main/source/frontend/streaming-ui/src/app/core/services/chat.service.ts` (modify `sendMessage`)
  - `main/source/frontend/streaming-ui/src/app/core/services/subscription.service.ts` (modify PUT/PATCH/DELETE methods)
  - `main/source/frontend/streaming-ui/src/app/core/services/notification.service.ts` (modify `markAsRead`, `markAllAsRead`)
  - `main/source/frontend/streaming-ui/src/app/core/services/chat-moderation.service.ts` (modify `ban`, `updateDuration`, `unban`)
- **Design decisions**:
  - `IdempotencyService.newKey()` → `crypto.randomUUID()` (128-bit random, collision-safe)
  - Service methods accept an optional `idempotencyKey?: string` parameter
  - When provided, set `Idempotency-Key` header on the request
  - Component callers are responsible for generating keys (not the interceptor)
  - `presence.service.ts` — heartbeat is periodic fire-and-forget, idempotency not needed
- **Validate**: `ng build`

### Task 4: Enable POST Retry in Auth Interceptor

- **Action**: Modify `auth.interceptor.ts` to retry POST/PUT/PATCH/DELETE after token refresh when the request has an `Idempotency-Key` header. The key ensures the retry is safe.
- **Files**:
  - `main/source/frontend/streaming-ui/src/app/core/interceptors/auth.interceptor.ts`
- **Logic change**: Currently the interceptor always re-throws for unsafe methods (line 41). New behavior:
  ```
  if (SAFE_METHODS.has(req.method)) {
    // Safe: just replay
    return next(cloneWithAuth(req, authService.accessToken()));
  }
  if (req.headers.has('Idempotency-Key')) {
    // Unsafe but idempotent: retry with same key
    return next(cloneWithAuth(req, authService.accessToken()));
  }
  // Unsafe, no idempotency key: can't safely retry
  return throwError(() => error);
  ```
- **Validate**: `ng build`

### Task 5: Wire Components to Use Idempotency Keys

- **Action**: Audit callers of state-changing endpoints in components and wire them to generate keys from `IdempotencyService`. Key callers:
  - `StreamDashboardPage` → `create()` / stream lifecycle methods
  - `ChannelPage` → `updateProfile()`
  - `ChatPanelComponent` → `sendMessage()`
  - `FollowButtonComponent` (or wherever follow is called)
  - `NotificationDropdownComponent` → `markAsRead()`
  - Ban-related components → moderation methods
- **Files**: Various component files (audit required, likely 5-8 components)
- **Validate**: `ng build`

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Gateway first Redis dependency — gateway goes from stateless to stateful | Medium | Fail-open pattern: Redis errors log + forward (never block). Redis already hardened (6.0a). |
| Response buffering overhead — `DataBufferUtils.join` must read full response body into memory | Low | Gateway already buffers for retry filter. Body sizes are small (API JSON responses). |
| Component wiring scope — need to find every state-changing caller | Medium | Task 5 audits callers systematically. Phased rollout OK — missing keys just mean no retry safety for that endpoint yet. |
| Cache invalidation for mutable resources — cached GET after POST would return stale data | N/A | Filter only caches POST/PUT/PATCH/DELETE responses. GET is never cached. |

## Validation

```bash
# Backend compile
./gradlew :gateway-service:compileJava

# Frontend build
cd main/source/frontend/streaming-ui && npx ng build

# Manual test (requires running platform):
# 1. Login, get token
# 2. Send POST with Idempotency-Key
# 3. Send same POST with same key → get cached 201 (no duplicate created)

# Unit test (future): IdempotencyFilterTest with embedded Redis (Testcontainers)
```

## Dependencies

- **6.0a Redis hardening** ✅ (Redis is running, password-protected, persisted)
- **No other blockers** — this is self-contained

## Deferred

- **Component wiring audit (Task 5)**: Wire the most impactful components (stream create, chat send, follow) in this phase. Remaining components can be wired incrementally.
- **GET caching**: Not in scope. Only state-changing methods are idempotency-protected.
- **Integration tests with Testcontainers Redis**: Deferred — same Docker-gated constraint as all other integration tests in the project.
