# Refresh Token Rotation

**Status:** Implemented  
**Source:** [AuthController.java](../main/source/backend/auth-service/src/main/java/com/streaming/auth/api/AuthController.java), [RefreshTokenService.java](../main/source/backend/auth-service/src/main/java/com/streaming/auth/service/RefreshTokenService.java), [CookieService.java](../main/source/backend/auth-service/src/main/java/com/streaming/auth/service/CookieService.java)

## Problem

Access tokens are short-lived (15 min) to limit blast radius if stolen. Refresh tokens are long-lived (7 days) but must be stored securely and rotated on every use to detect theft.

Without rotation, a stolen refresh token grants indefinite access. With rotation but no replay detection, an attacker who steals one refresh token can still maintain access by racing the legitimate user.

## Design

### Token Family Model

```
┌──────────────────────────────────────────────┐
│                 Token Family                   │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐  │
│  │ Refresh │ ──► │ Refresh │ ──► │ Refresh │  │
│  │  #1     │     │  #2     │     │  #3     │  │
│  └─────────┘     └─────────┘     └─────────┘  │
│       │               │               │        │
│       ▼               ▼               ▼        │
│   Access #1       Access #2       Access #3    │
└──────────────────────────────────────────────┘
```

Every login creates a new **token family** (`token_family_id` UUID). Each rotation revokes the old refresh token and issues a new one **within the same family**. Access tokens are always fresh — they are never reused.

### Replay Detection

If a refresh token that has **already been revoked** is presented, the server treats it as a replay attack and **revokes the entire token family**:

```
Attacker steals Refresh #2 (already used by legitimate user)
Attacker presents Refresh #2 → server sees revokedAt ≠ null
  → revokeAllActiveInFamily(familyId)
  → ALL tokens in this family are invalidated
  → User is logged out on all devices
  → Attacker cannot use Refresh #3 (also revoked)
```

This is the standard [refresh token rotation with automatic reuse detection](https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation) pattern. The legitimate user will be forced to re-authenticate — a minor inconvenience that prevents credential theft.

### Storage: HttpOnly Cookie (Web) + Response Body (Mobile)

| Client | Refresh Token Storage | Mechanism |
|--------|----------------------|-----------|
| Web (Angular SPA) | `refresh_token` HttpOnly cookie | Browser auto-attaches cookie; JS cannot read it (XSS-proof) |
| Mobile / Native | Response body `refreshToken` field | Client stores in secure enclave, sends in request body |

**Cookie attributes** (all env-configurable via `JwtIssuerProperties`):

| Attribute | Default | Purpose |
|-----------|---------|---------|
| `HttpOnly` | `true` | Blocks JavaScript access — XSS cannot exfiltrate |
| `Secure` | `false` (local) / `true` (prod) | HTTPS-only in production |
| `SameSite` | `Lax` | Blocks cross-site POSTs, allows top-level navigation |
| `Path` | `/api/auth` | Scoped to auth endpoints only |

### Cookie-First, Body-Fallback

The refresh endpoint reads the token in priority order:

```
POST /api/auth/v1/token/refresh
  ↓
CookieService.getRefreshToken()   ← reads HttpOnly cookie (web)
  ↓ (absent/blank)
request.refreshToken()            ← reads JSON body (mobile)
  ↓ (both absent)
throw InvalidRefreshTokenException
```

Web clients send an empty body `{}` — the cookie is auto-attached by the browser. Mobile clients send `{"refreshToken": "..."}` — they can't use cookies.

### Opaque Tokens, Hashed at Rest

Refresh tokens are 256-bit cryptographically random strings (`OpaqueTokenGenerator`). Only the SHA-256 hash is stored in the database — the plaintext is never persisted:

```
DB: refresh_tokens.token_hash = SHA-256("abc123...")
Cookie: refresh_token=abc123...
```

If the database is compromised, the attacker cannot use the hashes as refresh tokens.

## Server-Side Flow

### Login

```
AuthController.login()
  → AuthService.authenticate(credentials)
  → RefreshTokenService.issueNewFamilySession(userId)
    → Persist new refresh token (with new familyId)
    → Issue access token (JWT with PBAC claims)
  → CookieService.setRefreshTokenCookie(plaintext, ttl)
  → Return { accessToken, refreshToken, expiresIn, ... }
```

### Refresh

```
AuthController.refresh()
  → Read refresh token (cookie → body fallback)
  → RefreshTokenService.rotateSession(plaintext)
    → SHA-256 hash the plaintext
    → DB: PESSIMISTIC_WRITE lock on token hash
    → If revokedAt ≠ null → revokeAllActiveInFamily() → throw
    → If expired → throw
    → Revoke old token (set revokedAt = now)
    → Issue new refresh token (same familyId)
    → Issue new access token
  → CookieService.setRefreshTokenCookie(newPlaintext, ttl)
  → Return { accessToken, refreshToken, expiresIn, ... }
```

### Concurrency: `PESSIMISTIC_WRITE` Lock

The `rotateSession` method uses `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) on the token row. This serializes concurrent refresh attempts with the same token — the second caller sees `revokedAt ≠ null` and triggers family-wide revocation.

This is a **server-side defense-in-depth**. The primary fix for concurrent refresh is the frontend's [RxJS single-flight dedup](RXJS-SINGLE-FLIGHT-PATTERN.md), which prevents duplicate HTTP calls from ever leaving the browser.

## Why Not Access Tokens in Cookies?

Access tokens are stored in `localStorage` and attached via `Authorization: Bearer` header. Putting them in an HttpOnly cookie would:

1. **Break mobile clients** — they can't read Set-Cookie headers consistently
2. **Require CSRF protection** on every state-changing endpoint (cookies are auto-attached)
3. **Eliminate the ability to read claims client-side** — the SPA reads the access token to know the user's role/permissions

The refresh token in a cookie is safe because it's only sent to one path (`/api/auth`) and the refresh endpoint is idempotent (POST with no side effects beyond rotation).

## Configuration

All cookie attributes and token TTLs are in `application.yml`:

```yaml
streaming:
  jwt:
    access-token-ttl-seconds: 900       # 15 min
    refresh-token-ttl-seconds: 604800   # 7 days
    cookie-http-only: ${JWT_COOKIE_HTTP_ONLY:true}
    cookie-secure: ${JWT_COOKIE_SECURE:false}
    cookie-same-site: ${JWT_COOKIE_SAME_SITE:Lax}
    cookie-path: ${JWT_COOKIE_PATH:/api/auth}
```

Environment variable overrides allow per-environment tuning without code changes.

## Related Docs

- [RxJS Single-Flight Pattern](RXJS-SINGLE-FLIGHT-PATTERN.md) — Frontend dedup that prevents family wipeout
- [Auth Interceptor Pattern](AUTH-INTERCEPTOR-PATTERN.md) — How the frontend reacts to 401 responses
- [PBAC Authorization](PBAC-AUTHORIZATION.md) — How access token claims are structured

## References

- [Auth0: Refresh Token Rotation](https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation)
- [OWASP: JWT Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
