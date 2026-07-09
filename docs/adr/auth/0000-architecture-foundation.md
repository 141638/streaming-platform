# ADR-0000: Architecture Foundation — Auth Service

**Date**: 2026-07-09
**Status**: accepted
**Deciders**: hieuht, Claude

> **Entry point.** Read this before modifying any code in `auth-service/`.
> It covers the architectural style decision, package structure, entity patterns,
> and how each third-party service is integrated. Deeper ADRs
> ([0001](0001-redis-refresh-token-storage.md), [0002](0002-public-username-handle-and-login-hardening.md),
> [0003](0003-login-rate-limiting.md)) build on this foundation.

## Context

The auth service is the identity and authorization boundary: it authenticates users
(username/password → access + refresh tokens), issues PBAC JWTs with embedded entitlement
claims (`ent`, `attr`, `pv`, `ver`), manages refresh token rotation with replay detection,
and handles password reset flows. It is the only service that issues — rather than
validates — JWTs.

Key constraints:
- Spring Boot 3.3.6, Spring Web (Servlet — not reactive), Java 21
- JPA/Hibernate for PostgreSQL, blocking Redis (`RedisTemplate`), jjwt 0.12.5 for JWT signing
- HTTP Basic authentication for internal endpoints (not OAuth2 Resource Server)
- Must interoperate with gateway (token validation), stream-service (JWT consumption), and all downstream services

## Decision

We use **Layered MVC (Servlet)** architecture.

Unlike stream-service and chat-service (which use WebFlux + R2DBC), auth-service uses the
Servlet stack. The decision is intentional: auth workloads are I/O-light (single DB lookup +
JWT signing) and benefit more from the mature Spring Security Servlet integration
(`SecurityFilterChain`, HTTP Basic, `@AuthenticationPrincipal` on service-internal endpoints)
than from non-blocking I/O.

**Package structure:**

```
com.streaming.auth/
├── AuthApplication.java                      (entry point, @EnableConfigurationProperties)
├── api/
│   ├── AuthController.java                   (login, refresh, logout)
│   ├── PingController.java                   (health check)
│   ├── PasswordResetController.java          (password reset request + confirm)
│   ├── InternalAccessTokenController.java    (operator/internal token minting, HTTP Basic)
│   └── GlobalExceptionHandler.java           (@RestControllerAdvice)
├── service/
│   ├── AuthService.java                      (authenticate: verify credentials → issue tokens)
│   ├── AccessTokenIssuanceService.java       (PBAC JWT minting for users + service accounts)
│   ├── RefreshTokenService.java              (token family rotation, delegates to Redis Lua)
│   ├── PasswordResetService.java             (reset flow + email dispatch)
│   ├── PasswordResetTokenService.java        (single-purpose JWT for password reset)
│   ├── CookieService.java                    (@RequestScope, HttpOnly refresh_token cookie)
│   ├── MailCommonService.java                (@Async JavaMailSender wrapper)
│   └── SubjectAttributeResolver.java         (maps user entity → JWT attr claim values)
├── token/
│   ├── IssuedAccessToken.java                (record: compact JWT + TTL + policyVersion)
│   ├── IssuedSessionTokens.java              (record: access + refresh + TTLs)
│   ├── JwtIssuerProperties.java              (@ConfigurationProperties "streaming.jwt")
│   ├── JwtSigningKey.java                    (@Bean SecretKey from hmacSecret)
│   ├── OpaqueTokenGenerator.java             (SecureRandom 48-byte URL-safe Base64)
│   └── policy/
│       ├── EntitlementLinesMaterializer.java (policy.definition JSON → JWT ent lines)
│       └── PolicyStatementsDocument.java     (JSON record: {"statements":[...]})
├── authorization/
│   ├── AuthAction.java                       (PBAC action verb enum)
│   ├── AuthResourceDomain.java               (PBAC domain enum)
│   ├── AuthResourceKind.java                 (PBAC kind enum)
│   ├── AuthorizationResource.java            (pattern builder: domain:kind:scope)
│   ├── EntitlementGrammarVersion.java        (JWT "ver" claim enum, currently V1)
│   └── EntitlementStatements.java            (compact "allow" line formatter)
├── persistence/
│   ├── entity/
│   │   ├── UserAccountEntity.java            (JPA @Entity — user_account table)
│   │   ├── UserAccountRoleEntity.java        (JPA @Entity, @IdClass composite key)
│   │   ├── PolicyEntity.java                 (JPA @Entity — policy definitions)
│   │   ├── PolicyAttachmentEntity.java       (JPA @Entity — policy-to-principal binding)
│   │   └── CatalogSubjectAttributeEntity.java (JPA @Entity — allowed JWT attr keys)
│   └── repository/
│       ├── UserAccountRepository.java        (JpaRepository)
│       ├── UserAccountRoleRepository.java    (JpaRepository)
│       ├── PolicyRepository.java             (JpaRepository)
│       ├── PolicyAttachmentRepository.java   (JpaRepository, custom JPQL)
│       └── CatalogSubjectAttributeRepository.java (JpaRepository)
├── infrastructure/
│   └── redis/
│       └── RefreshTokenRedisService.java     (Redis hash/set ops + Lua script rotation)
├── config/
│   ├── SecurityConfig.java                   (Servlet SecurityFilterChain, HTTP Basic)
│   ├── PasswordEncoderConfig.java            (BCrypt strength 10)
│   └── RedisConfig.java                      (RedisTemplate<String,String> with String serializers)
├── dto/                                      (Java records: requests, responses, JWT payloads)
│   ├── jwt/
│   │   ├── StreamingAccessTokenPayload.java  (full JWT claims record)
│   │   └── SubjectAttributes.java            (roles/tier/verified_streamer)
│   └── internal/                             (internal token minting DTOs)
└── exception/                                (@ResponseStatus exceptions: 401, 403, 404, 422)
```

**Layer dependency rules:**

```
api/ ──→ service/ ──→ persistence/entity/
  │          │
  │          ├──→ token/          (JWT signing, policy materialization)
  │          ├──→ authorization/  (PBAC vocabulary)
  │          └──→ infrastructure/ (Redis, email)
  │
  └──→ config/   (wiring — no domain logic)
```

- `api/` depends on `service/` and `dto/` — never on `persistence/` directly
- `service/` orchestrates across `persistence/`, `token/`, `authorization/`, and `infrastructure/`
- `token/` is a self-contained sub-domain: JWT signing key, issuer properties, policy → `ent` line materialization
- `authorization/` is the canonical PBAC vocabulary — stream-service holds mirrors of these enums
- `config/` wires everything; no business logic

## Alternatives Considered

### Alternative 1: Reactive (WebFlux + R2DBC)

- **Pros**: Would match stream-service and chat-service — one stack across all services.
- **Cons**: Auth workloads are I/O-light. `AuthService.authenticate()` does one `userRepository.findByUsername()` (a single-row indexed lookup) + bcrypt verify + JWT signing — none of which benefit from non-blocking I/O. Spring Security's reactive support is less mature for the patterns auth needs (HTTP Basic for internal endpoints, `@Async` for email, `HttpServletRequest` for cookie management).
- **Why not**: The Servlet stack is the right tool for this job. Consistency across services is valuable, but not at the cost of fighting the framework for every security integration.

### Alternative 2: Domain-Driven Design (tactical)

- **Pros**: `UserAccount` as a rich aggregate with invariants (unique username, verified streamer gating).
- **Cons**: Auth is mostly orchestration — verify credentials, sign JWT, store refresh token. The domain is thin: one aggregate (`UserAccount`), a catalog (policy definitions), and scaffolding (role memberships). DDD would add ceremony without proportional benefit.
- **Why not**: YAGNI. If the user domain grows complex (MFA, OIDC federation, organization memberships), introduce aggregates then. The layered structure already separates `persistence/entity/` from `service/`.

### Alternative 3: Hexagonal / Ports & Adapters

- **Pros**: Swappable implementations — PostgreSQL → LDAP, jjwt → Nimbus, JavaMail → SendGrid.
- **Cons**: Only one implementation exists for each port. `AccessTokenIssuanceService` already uses constructor-injected dependencies (`JwtSigningKey`, `JwtIssuerProperties`, `SubjectAttributeResolver`) — swapping the JWT library would touch one class regardless of whether a formal `TokenIssuer` interface exists.
- **Why not**: Hexagonal is applied where it matters: `RefreshTokenService` delegates to `RefreshTokenRedisService` (the Redis adapter). Adding port interfaces for every repository and token service would double the class count without a second implementation to justify it.

### Alternative 4: Anemic (controller → repository directly)

- **Pros**: Fewer files, less indirection.
- **Cons**: `AuthController.login()` would need to: validate input, query user repository, verify bcrypt, issue access token, issue refresh token, set cookie, publish event — all in one method. Extracting password reset or internal token minting into separate controllers would duplicate token-issuance logic.
- **Why not**: Already rejected by the current implementation. `AuthService` and `AccessTokenIssuanceService` encapsulate reusable logic consumed by both `AuthController` and `InternalAccessTokenController`.

## Entity & Persistence Patterns

### JPA (not R2DBC)

Auth is the only control-plane service using JPA/Hibernate rather than R2DBC. This is a
deliberate pairing with the Servlet stack — `JpaRepository` provides mature, well-tested
data access for the low-throughput, high-correctness auth workload.

### No `Persistable`, No `@Version`

Unlike stream-service and chat-service, auth entities do NOT implement `Persistable<UUID>`
or use `@Version` optimistic locking. Auth is stateless for token operations — the only
mutable entity state (user profile fields) changes infrequently and conflicts are rare.
Token state lives in Redis, not in entity versions.

### Soft Delete

`UserAccountEntity` uses a `deleteFlag` boolean rather than physical row deletion.
All repository queries filter `deleteFlag = false`.

### Composite Keys

`UserAccountRoleEntity` uses `@IdClass(UserAccountRoleEntity.Pk.class)` with a composite
primary key `(userAccountId, roleSlug)`.

### Flyway

Flyway uses the same JDBC side-channel pattern as stream-service and chat-service:
`spring.flyway.url` with a dedicated JDBC connection targeting schema `auth`.
V1–V8 migrations exist; V9 is next (`username` subject attribute seed).

## Third-Party Integration Catalog

How each external dependency is integrated into this service.

### PostgreSQL

| Aspect | Detail |
|--------|--------|
| **Role** | System of record — user accounts, role memberships, policy definitions, subject attribute catalog |
| **Schema** | `auth` — owned exclusively by this service; cross-schema FKs avoided |
| **Driver** | JDBC via JPA/Hibernate (`spring-boot-starter-data-jpa`) — blocking, not reactive |
| **Migrations** | Flyway via JDBC side-channel (V1–V8; V9 next: `username` subject attribute seed) |
| **Repositories** | `JpaRepository` with custom JPQL for policy resolution and role lookups |
| **Entity pattern** | Standard JPA `@Entity` — no `Persistable`, no `@Version` |

### Redis

| Aspect | Detail |
|--------|--------|
| **Role** | Ephemeral credential store — refresh tokens, rate-limit counters (planned) |
| **Driver** | Blocking `RedisTemplate<String, String>` via `spring-boot-starter-data-redis` |
| **Token rotation** | Atomic Lua script (`rotate_refresh_token.lua`) — validates old token, revokes it, persists new token, detects replays (family-wide revocation). Loaded from classpath via `DefaultRedisScript`. |
| **Key families** | `rt:{sha256}` — individual token hash → token metadata (Hash). `rt_family:{familyId}` — family member set (Set). `rt_user:{userUuid}` — user token set (Set). |
| **Persistence** | AOF + RDB (configured at infrastructure level per [ADR common/0002](../../adr/common/0002-redis-ephemeral-data-store.md)). Tokens survive Redis restarts but are treated as disposable — a lost token just forces re-login. |
| **ADR** | [0001 — Redis-Based Refresh Token Storage](0001-redis-refresh-token-storage.md) |

### Email (SMTP)

| Aspect | Detail |
|--------|--------|
| **Role** | Password reset email delivery |
| **Library** | `spring-boot-starter-mail` — `JavaMailSender` with `@Async` for non-blocking send |
| **Template** | HTML email via `MailCommonService` |

### Service Registry (Eureka)

| Aspect | Detail |
|--------|--------|
| **Role** | Client-side service discovery — registers with Eureka, resolved by gateway via `lb://auth-service` |
| **Library** | `spring-cloud-starter-netflix-eureka-client` |

## Security Model

### HTTP Basic (not OAuth2 Resource Server)

Unlike stream-service and chat-service, auth-service does NOT validate JWTs on its own
endpoints. It uses HTTP Basic for internal endpoints (`/v1/internal/*`) and leaves public
endpoints (`/v1/login`, `/v1/token/refresh`, etc.) as `permitAll()`.

The reasoning: auth-service is the JWT **issuer**, not a consumer. It trusts HTTP Basic
for internal callers (gateway, other services) because those credentials are configured
via `spring.security.user.*` in the same private network.

### PBAC Token Issuance

`AccessTokenIssuanceService` builds HS256 JWTs with claims:

| Claim | Meaning |
|-------|---------|
| `sub` | User UUID or service-account subject |
| `iss` | `streaming-auth-service` |
| `aud` | `streaming-platform` (or `stream-service-internal` for service tokens) |
| `iat`, `exp` | Standard timestamps |
| `jti` | Unique token ID |
| `ver` | Entitlement grammar version (currently `V1`) |
| `pv` | Policy version integer |
| `ent` | Entitlement lines: `["allow stream:session:self create read update delete issue_key manage_lifecycle", ...]` |
| `attr` | Subject attributes: `{"roles":["streamer"], "tier":"standard", "verified_streamer":true}` |

Header: `typ: at+jwt` (RFC 9068).

### Cookie-Based Refresh Tokens

Refresh tokens are stored in an HttpOnly, SameSite=Lax cookie (`refresh_token`).
Non-browser clients can submit the token in the request body as a fallback.
`CookieService` (`@RequestScope`) manages read/set/clear operations.

## Forward-Looking Sketch

What's planned but not yet built. See [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) for the
authoritative phase checklist.

| Item | Phase | Description |
|------|-------|-------------|
| Username in JWT `attr` | A1 | Seed `username` subject attribute + resolver → `attr.username` in tokens |
| Uniform 401 on login | A2 | Collapse 404-vs-401 enumeration oracle → single `InvalidCredentialsException` |
| Login rate limiter | A2 | Redis-Lua sliding-window rate limiter with graduated slowdown + IP-scoped lockout |
| `pbac-common` extraction | 6.2 | Extract duplicated enums (`AuthAction`, `AuthResourceDomain`, `AuthResourceKind`) into a shared Gradle module — auth-service is the canonical source |
| Email-OR-username login | Deferred | Allow login with either email or username (auth/0002 deferred list) |
| MFA / OIDC federation | Deferred | Multi-factor auth, external IdP integration (auth/0002 deferred list) |

## References

- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural style recommendations
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — authorization model and JWT claims design
- [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) — master phase plan; Phase 1 + channel page items
- [ADR-0001](0001-redis-refresh-token-storage.md) — Redis refresh token storage with atomic Lua rotation
- [ADR-0002](0002-public-username-handle-and-login-hardening.md) — username handle + login enumeration fix (Proposed)
- [ADR-0003](0003-login-rate-limiting.md) — Redis-Lua login brute-force rate limiting (Proposed)
- [auth-service source](../../../main/source/backend/auth-service/src/main/java/com/streaming/auth/)
