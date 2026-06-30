# Auth Interceptor Pattern

**Status:** Implemented  
**Source:** [auth.interceptor.ts](../main/source/frontend/streaming-ui/src/app/core/interceptors/auth.interceptor.ts), [auth.service.ts](../main/source/frontend/streaming-ui/src/app/core/services/auth.service.ts)

## Problem

An Angular SPA needs to:

1. Attach the Bearer token to every outgoing request
2. Detect when the access token has expired (401 response)
3. Attempt a silent refresh using the HttpOnly refresh-token cookie
4. Retry the original request if safe, or propagate the error if unsafe
5. Force logout if the refresh fails or the token is invalid
6. Avoid infinite loops (never intercept the refresh call itself)
7. Prevent duplicate refresh calls when multiple requests fail simultaneously

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                     Auth Interceptor                           │
│                                                                │
│  Outgoing Request                                               │
│       │                                                         │
│       ▼                                                         │
│  ┌──────────────┐                                               │
│  │ cloneWithAuth │  ← Attach Bearer token from localStorage     │
│  └──────┬───────┘                                               │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────┐                                               │
│  │   next(req)  │  ← Forward to backend                         │
│  └──────┬───────┘                                               │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────┐      ┌──────────────────────────────────┐    │
│  │  catchError  │ ───► │ Status ≠ 401 → propagate          │    │
│  │  (401 only)  │      │ URL = /token/refresh → propagate   │    │
│  └──────┬───────┘      │ error_code = invalid_token → logout │   │
│         │              │ error_code = token_expired → refresh│   │
│         │              └──────────────────────────────────┘    │
│         ▼                                                       │
│  ┌──────────────────┐                                           │
│  │ authService       │  ← Single-flight refresh (RxJS share())   │
│  │ .refresh()        │                                           │
│  └──────┬───────────┘                                           │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Success:                                                  │  │
│  │    GET/HEAD/OPTIONS → retry original request (new token)   │  │
│  │    POST/PUT/PATCH/DELETE → propagate error (no retry)      │  │
│  │                                                            │  │
│  │  Failure:                                                  │  │
│  │    logout() → clear state, redirect to /login              │  │
│  └──────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

## State Machine

```
                     ┌──────────────────┐
                     │  Request passes   │
                     │  through (200)    │
                     └──────────────────┘
                              ▲
                              │ token valid
                              │
  ┌──────────┐   attach    ┌──┴──────────┐
  │ Outgoing │────────────►│  Backend     │
  │ Request  │  Bearer     │  Response    │
  └──────────┘             └──┬──────┬────┘
                              │      │
                        200/3xx│      │ 401
                              │      │
                              ▼      ▼
                    ┌──────────────┬──────────────┐
                    │              │               │
              error_code:    error_code:      No error_code
             "token_expired" "invalid_token"   (or missing)
                    │              │               │
                    ▼              ▼               ▼
            ┌──────────────┐ ┌──────────┐  ┌──────────┐
            │ refresh()    │ │ logout() │  │ logout() │
            │ (single-     │ │ force    │  │ force    │
            │  flight)     │ │ re-login │  │ re-login │
            └──┬───────┬───┘ └──────────┘  └──────────┘
               │       │
          success    failure
               │       │
               ▼       ▼
      ┌────────────┐ ┌──────────┐
      │ Method?    │ │ logout() │
      └──┬─────┬───┘ └──────────┘
         │     │
    GET/HEAD  POST/PUT
    /OPTIONS  /PATCH/DELETE
         │     │
         ▼     ▼
   ┌────────┐ ┌──────────────┐
   │ Retry  │ │ Propagate    │
   │ with   │ │ 401 to       │
   │ new AT │ │ caller       │
   └────────┘ └──────────────┘
```

## Retry Policy: Safe vs Unsafe Methods

| Method | On `token_expired` | Rationale |
|--------|-------------------|-----------|
| GET, HEAD, OPTIONS | Refresh + **retry** original request | Idempotent by HTTP spec — safe to replay |
| POST, PUT, PATCH, DELETE | Refresh only, **propagate** 401 error | Not safe to retry without idempotency keys |

**Why not retry POST?** A retried POST could create duplicate resources (two stream sessions, two purchases, two chat messages). The component that initiated the request knows the user's intent and can generate an idempotency key before retrying. See [IDEMPOTENCY-PATTERN.md](IDEMPOTENCY-PATTERN.md).

Once idempotency keys are implemented, POST retries can be safely enabled in the interceptor.

## Infinite-Loop Guard

The interceptor **must never** intercept the `/token/refresh` call itself:

```typescript
if (req.url.includes('/api/auth/v1/token/refresh')) {
  return throwError(() => error);
}
```

Without this guard, a failed refresh would trigger another refresh attempt, creating an infinite loop.

## Distinguishable 401 Error Codes

The gateway returns a machine-readable `error_code` in the 401 JSON body:

```json
// Expired token (can be refreshed)
{"error": "Unauthorized", "error_code": "token_expired", "message": "Access token has expired"}

// Invalid/missing token (must re-authenticate)
{"error": "Unauthorized", "error_code": "invalid_token", "message": "A valid Bearer token is required"}
```

The gateway walks the exception cause chain for the word "expired" (case-insensitive). This is intentionally coarse — a false negative (treating expired as invalid) just forces an unnecessary re-login, which is safer than trying to refresh a genuinely invalid token.

## `WWW-Authenticate` Suppression

Spring Security's `BasicAuthenticationEntryPoint` adds `WWW-Authenticate: Basic` to 401 responses. Browsers interpret this as a credential challenge and display their native login dialog over the Angular SPA — a UX bug.

**Fix**: The auth service uses `exceptionHandling().authenticationEntryPoint()` to return a JSON 401 without the header. The gateway also strips `WWW-Authenticate` as defense-in-depth.

## Registration

The interceptor is registered in `app.config.ts`:

```typescript
provideHttpClient(
  withInterceptors([authInterceptor]),
  withFetch(),
)
```

Functional interceptors (`HttpInterceptorFn`) are preferred over class-based interceptors — they're tree-shakeable and use `inject()` for DI.

## Related Docs

- [Refresh Token Rotation](REFRESH-TOKEN-ROTATION.md) — Server-side token lifecycle
- [RxJS Single-Flight Pattern](RXJS-SINGLE-FLIGHT-PATTERN.md) — How `refresh()` deduplicates concurrent calls
- [IDEMPOTENCY-PATTERN.md](IDEMPOTENCY-PATTERN.md) — Prerequisite for enabling POST retry

## References

- [Angular: HttpInterceptorFn](https://angular.dev/api/common/http/HttpInterceptorFn)
- [RFC 6750: Bearer Token Usage](https://datatracker.ietf.org/doc/html/rfc6750)
