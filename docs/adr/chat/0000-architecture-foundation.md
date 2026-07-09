# ADR-0000: Architecture Foundation — Chat Service

**Date**: 2026-07-02 (renumbered 2026-07-09)
**Status**: accepted
**Deciders**: hieuht, Claude

> **Entry point.** Read this before modifying any code in `chat-service/`.
> It covers the architectural style decision, package structure, entity patterns,
> and how each third-party service is integrated. Deeper ADRs
> ([0001](0001-cache-aside-redis-zset.md), [0002](0002-jwt-derived-author-identity.md))
> build on this foundation.

## Context

The chat service is a new control-plane component in the streaming platform. We need to choose an architectural style for its codebase. The existing platform services — particularly stream-service — already follow a specific layered convention (`api/` → `application/` → `domain/` → `infrastructure/`). We evaluated five alternatives: Layered Reactive (stream-service pattern), Domain-Driven Design, Clean Architecture, Hexagonal/Ports & Adapters, and Anemic (prototype style with controller-talking-directly-to-infrastructure). The decision matters because the team will own this service long-term, and consistency across services reduces cognitive overhead when moving between codebases.

Key constraints:
- Spring Boot 3.3.6, WebFlux (reactive), Java 21
- R2DBC for PostgreSQL, Reactive Redis, eventual Kafka consumer
- JWT authentication with `@AuthenticationPrincipal Jwt jwt`
- The service is a "separate team" effort — full autonomy but must interoperate with gateway, auth, and stream services

## Decision

We use **Layered Reactive** architecture with the package structure `api/` → `application/` → `domain/` → `infrastructure/`, matching the stream-service conventions documented in [`docs/SERVICE-ARCHITECTURE.md`](../../SERVICE-ARCHITECTURE.md). Domain entities use the `Persistable<UUID>` + `@Transient isNew` pattern. DTOs are Java records. All dependencies are injected via constructor injection with Lombok `@RequiredArgsConstructor`.

## Alternatives Considered

### Alternative 1: Domain-Driven Design (tactical)
- **Pros**: Rich domain model with aggregates, value objects, domain events — would handle complex chat invariants (moderation, room lifecycle) explicitly.
- **Cons**: Significant upfront modeling cost for a service that is currently CRUD-heavy. Team is small; DDD tactical patterns add ceremony without proportional benefit at this stage.
- **Why not**: YAGNI. The current domain (rooms + messages) is straightforward. We can introduce tactical DDD patterns later if complexity grows — the layered structure already separates domain from infrastructure, so the migration path is short.

### Alternative 2: Clean Architecture (Use Case / Entity / Interface Adapters)
- **Pros**: Strong dependency inversion — domain has zero framework dependencies, use cases are explicit classes.
- **Cons**: Proliferation of interfaces and input/output ports for every use case. The stream-service team does not use this style; two conventions in one platform is worse than picking the "less ideal" one.
- **Why not**: Platform consistency beats architectural purity. The layered approach already achieves the key Clean Architecture goal (domain depends on nothing, infrastructure depends on domain).

### Alternative 3: Hexagonal / Ports & Adapters
- **Pros**: Explicit ports make testing with fakes trivial; swapping implementations (e.g., Redis → Kafka for message fan-out) is a config change.
- **Why not**: Partially applied. We define ports only where multiple implementations are expected (e.g., `RedisMessageCache` as an adapter behind `ChatService` orchestration). Full hexagonal would require port interfaces for every repository, which is ceremony the platform doesn't need yet.

### Alternative 4: Anemic (controller → repository directly)
- **Pros**: Fastest to prototype. The original `ChatController` did exactly this — 65 lines of inline Redis calls.
- **Cons**: Business logic scatters across controllers. No separation between HTTP concerns and domain rules. Testing requires full WebTestClient. Reusing logic (e.g., Kafka consumer sending messages) means copy-paste.
- **Why not**: Already rejected by the scaffold rewrite. The prototype proved this works for a demo but doesn't scale past 2-3 endpoints.

## Consequences

### Positive
- **Consistency**: Same package layout, entity patterns, and naming as stream-service — a developer can read either codebase without re-learning conventions.
- **Testability**: Each layer can be unit-tested in isolation. `ChatService` can be tested with mock repositories; `ChatController` with `@WebFluxTest`.
- **Gradual complexity**: Can introduce DDD aggregates or hexagonal ports later without restructuring — the `domain/` package is already free of framework annotations beyond `@Table`.

### Negative
- **Not pure**: The "domain" entities carry Spring Data annotations (`@Table`, `@Id`), so they're not framework-free. This is pragmatic — the platform uses Spring Data R2DBC exclusively, and abstracting it behind a port would add indirection without a second implementation.
- **Package explosion**: 15 source files for a service with 2 endpoints. This is the nature of the layered style — pay structure upfront, amortize as the service grows.

### Risks
- **Layered vs. DDD creep**: If the chat domain grows complex (rich moderation, room permissions, message threading), the anemic-ish `ChatRoom`/`ChatMessage` entities may become god objects. **Mitigation**: monitor entity size; introduce domain services or aggregates when entities exceed ~100 lines of behavior.
- **Duplication with stream-service**: Both services share `JwtProperties`, `SecurityConfig`, `Persistable<UUID>` patterns. **Mitigation**: the planned `pbac-common` shared library ([PBAC-AUTHORIZATION.md §12](../../PBAC-AUTHORIZATION.md#12-shared-pbac-library--extraction-plan)) will extract these when per-service `ent` enforcement is implemented.

## Third-Party Integration Catalog

How each external dependency is integrated into this service.

### PostgreSQL

| Aspect | Detail |
|--------|--------|
| **Role** | System of record — all chat rooms and messages are durable here |
| **Schema** | `chat` — owned exclusively by this service |
| **Driver** | R2DBC (`r2dbc-postgresql`) — reactive, non-blocking |
| **Migrations** | Flyway via JDBC side-channel (`spring.flyway.url` — a separate blocking connection, not the primary `DataSource`) |
| **Repositories** | `ReactiveCrudRepository` with derived query methods (`findByRoomIdOrderByCreatedAtDesc`) |
| **Entity pattern** | `Persistable<UUID>` + `@Transient isNew` (UUID assigned pre-persist) |

### Redis

| Aspect | Detail |
|--------|--------|
| **Role** | Hot cache (disposable latency buffer) — NOT the system of record |
| **Pattern** | Cache-aside: PG-first writes, Redis-first reads with PG fallback + async backfill |
| **Data structure** | ZSET per room (`chat:room:{roomKey}:recent`), scored by epoch-millis |
| **Retention** | 100 messages per room, trimmed on each write via `ZREMRANGEBYRANK` |
| **Resilience** | All Redis operations wrapped in `.onErrorResume()` — failures return empty/`false`, never propagate |
| **Serialization** | Jackson JSON with `JavaTimeModule` |
| **ADR** | [0001 — Cache-Aside Pattern with Redis ZSET](0001-cache-aside-redis-zset.md) |

### Kafka _(planned — not yet implemented)_

| Aspect | Detail |
|--------|--------|
| **Role** | Consumer of `stream.control` topic — drives room lifecycle |
| **Pattern** | `StreamControlListener` (reactive Kafka consumer): `STREAM_CREATED` → create room, `STREAM_ENDED` → archive room |
| **Status** | Phase 3.3 — not yet built; documented in [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md#33--room-lifecycle-from-stream-events) |
| **Out-of-order safety** | Archive-before-create = no-op; duplicate creates are idempotent via existing `getOrCreate` |

### Service Registry (Eureka)

| Aspect | Detail |
|--------|--------|
| **Role** | Client-side service discovery — registers with Eureka, resolved by gateway via `lb://chat-service` |
| **Library** | `spring-cloud-starter-netflix-eureka-client` |

## Forward-Looking Sketch

What's planned but not yet built. See [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) for the
authoritative phase checklist.

| Item | Phase | Description |
|------|-------|-------------|
| Room lifecycle from Kafka | 3.3 | `StreamControlListener` — rooms auto-created on `STREAM_CREATED`, auto-archived on `STREAM_ENDED` |
| PBAC enforcement | 3.4 | `EntitlementMatcher` mapping `chat:room:{key}` / `chat:message:room:{key}` to `read`/`send` actions |
| Cache integration tests | 3.6 | 8 Testcontainers-based test scenarios covering hot/cold/error paths |
| Cache warm-up completion | 3.7 | Backfill cache from `getMessagesBefore()` PG fallback (currently only `getRecentMessages` backfills) |
| WebSocket upgrade | 6.4 | Replace REST polling with WebSocket (STOMP or raw) + Redis Pub/Sub for cross-instance fan-out |
| `pbac-common` extraction | 6.2 | Extract duplicated `JwtProperties`, `EntitlementMatcher`, `Persistable<UUID>` patterns into a shared Gradle module |

## References

- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural style recommendations
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — authorization model and JWT claims design
- [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) — master phase plan; Phase 3 checklist
- [ADR-0001](0001-cache-aside-redis-zset.md) — Redis ZSET cache-aside pattern (deep dive)
- [ADR-0002](0002-jwt-derived-author-identity.md) — JWT `sub` as author identity
- [chat-service source](../../../main/source/backend/chat-service/src/main/java/com/streaming/chat/)
