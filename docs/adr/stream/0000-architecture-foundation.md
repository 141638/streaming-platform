# ADR-0000: Architecture Foundation — Stream Service

**Date**: 2026-07-09
**Status**: accepted
**Deciders**: hieuht, Claude

> **Entry point.** Read this before modifying any code in `stream-service/`.
> It covers the architectural style decision, package structure, entity patterns,
> and how each third-party service is integrated. Deeper ADRs
> ([0001](0001-stream-state-machine.md) through [0007](0007-public-channel-read-and-channel-service-seam.md))
> build on this foundation.

## Context

The stream service is the core of the control plane: it manages stream session lifecycle
(create → go-live → end), issues publish tokens for the SRS media server, publishes
lifecycle events to Kafka for downstream consumers, and exposes a channel read endpoint
for viewer-facing pages. It is the most integration-heavy service in the platform.

Key constraints:
- Spring Boot 3.3.6, WebFlux (reactive), Java 21
- R2DBC for PostgreSQL, reactive Kafka producer, SRS webhook receiver
- JWT authentication with `@AuthenticationPrincipal Jwt jwt` (OAuth2 Resource Server)
- Must interoperate with gateway, auth-service, SRS, and downstream Kafka consumers

## Decision

We use **Layered Reactive + Ports & Adapters (hexagonal-lite)** architecture.

**Package structure:**

```
com.streaming.stream/
├── StreamApplication.java                   (entry point, @EnableKafka)
├── api/
│   ├── StreamController.java                (REST: CRUD, lifecycle, publish keys, categories)
│   ├── SrsWebhookController.java            (SRS on_publish / on_unpublish webhook receiver)
│   └── dto/                                 (Java records: requests, responses, webhook payloads)
├── service/
│   ├── StreamService.java                   (core business logic: CRUD, lifecycle orchestration)
│   └── PublishTokenService.java             (HS256 JWT issue/validate for SRS publish tokens)
├── persistence/
│   ├── entity/
│   │   ├── StreamSessionEntity.java         (aggregate root: Persistable<UUID>, @Version, state machine)
│   │   ├── StreamCategoryEntity.java        (managed vocabulary lookup entity)
│   │   └── StreamStatus.java                (enum state machine with wireValue + allowedTransitions)
│   └── repository/
│       ├── StreamSessionRepository.java     (ReactiveCrudRepository)
│       └── StreamCategoryRepository.java    (ReactiveCrudRepository)
├── messaging/
│   ├── StreamEvent.java                     (canonical Kafka event record with factory methods)
│   └── StreamEventPublisher.java            (reactive KafkaTemplate wrapper)
├── security/
│   ├── AuthAction.java                      (PBAC action verb enum — PBAC-COMMON-CANDIDATE)
│   ├── AuthResourceDomain.java              (PBAC domain enum — PBAC-COMMON-CANDIDATE)
│   ├── AuthResourceKind.java                (PBAC kind enum — PBAC-COMMON-CANDIDATE)
│   ├── EntitlementMatcher.java              (JWT "ent" claim parser + scope matcher)
│   ├── RequiredAuthority.java               (record: domain + kind + action + ownerSubject)
│   └── StreamAuthorization.java            (reactive guard: requireAccess(Jwt, RequiredAuthority))
└── config/
    ├── SecurityConfig.java                  (WebFlux SecurityWebFilterChain, reactive JWT decoder)
    ├── R2dbcConfig.java                     (StreamStatus ↔ String converter registration)
    ├── JwtProperties.java                   (@ConfigurationProperties "streaming.jwt")
    ├── PublishTokenProperties.java          (@ConfigurationProperties "streaming.publish-token")
    ├── StreamAuthenticationEntryPoint.java  (JSON 401 with error_code distinction)
    ├── StreamStatusToStringConverter.java   (R2DBC WritingConverter)
    └── StringToStreamStatusConverter.java   (R2DBC ReadingConverter)
```

**Layer dependency rules:**

```
api/ ──→ service/ ──→ persistence/entity/
  │          │
  │          ├──→ messaging/
  │          └──→ security/
  │
  └──→ config/   (wiring — no domain logic)
```

- `api/` depends on `service/` and `dto/` — never on `persistence/` directly
- `service/` orchestrates across `persistence/`, `messaging/`, and `security/`
- `persistence/entity/` contains domain logic (state machine) — not anemic
- `config/` wires everything; no business logic

## Alternatives Considered

### Alternative 1: Domain-Driven Design (tactical)
- **Pros**: Rich aggregates, domain events, explicit bounded context — would handle stream lifecycle invariants cleanly.
- **Cons**: Significant ceremony for a service with one primary aggregate (`StreamSession`). The current entity-based state machine already captures the lifecycle invariants without DDD overhead.
- **Why not**: The entity state machine pattern (`transitionTo()`, `goLive()`, `end()`, `cancel()`) achieves the same correctness at lower complexity. DDD can be introduced later if a second aggregate (e.g., StreamTemplate, Channel) gains complex behavior.

### Alternative 2: Clean Architecture (Use Case / Entity / Interface Adapters)
- **Pros**: Zero framework dependencies in domain; explicit use-case classes.
- **Cons**: Would require port interfaces for `StreamSessionRepository`, `StreamEventPublisher`, `PublishTokenService` — each with input/output boundaries. At 30 source files, the interface-to-implementation ratio would approach 1:1.
- **Why not**: The layered approach already achieves the core Clean Architecture goal (domain entities have no framework import beyond `@Table`/`@Id`). Adding port interfaces for every collaborator is ceremony the platform doesn't need yet.

### Alternative 3: Anemic MVC (controller → repository directly)
- **Pros**: Fastest to prototype. Fewer files, less indirection.
- **Cons**: Business logic scatters across controllers. The stream state machine, one-live-stream rule, publish token issuance, and Kafka event publishing would all live in `StreamController` → ~500-line controller. Testing requires full `@WebFluxTest`. Reusing logic across REST and webhook controllers means copy-paste.
- **Why not**: Already rejected by the Phase 2.3/2.4 implementation. The service layer absorbs complexity that would otherwise make controllers unmaintainable.

### Alternative 4: Pure Hexagonal / Ports & Adapters
- **Pros**: Every external dependency behind an interface — trivial to swap PostgreSQL for MySQL, Kafka for RabbitMQ.
- **Cons**: 30+ port interfaces for a service that has no realistic plan to swap any of its three adapters (PostgreSQL, Kafka, SRS). The SRS integration already uses the hexagonal pattern where it matters (webhook endpoint + publish token service are separate concerns behind the controller).
- **Why not**: Hexagonal is applied selectively ("hexagonal-lite"). Ports exist where multiple implementations are plausible (e.g., a test double for SRS webhooks). For PostgreSQL and Kafka, the repository and publisher abstractions are sufficient.

## Entity & Persistence Patterns

### `Persistable<UUID>` + `@Transient isNew`

Every entity implements Spring Data's `Persistable<UUID>` with a `@Transient boolean isNew` flag.
Since UUID IDs are assigned at construction time (not auto-generated by the database),
Spring Data R2DBC cannot distinguish new vs. existing entities by null-checking the ID.
The `isNew` flag is set to `true` in a `@PostLoad`-style initializer (or at construction)
and cleared after the first `save()`.

```java
// Pattern used in StreamSessionEntity and StreamCategoryEntity
@Table("stream.stream_session")
public class StreamSessionEntity implements Persistable<UUID> {
    @Id
    private UUID id;
    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    public void markPersisted() { this.isNew = false; }
}
```

### `@Version` Optimistic Locking

`StreamSessionEntity` carries `@Version Long version`. R2DBC increments this on each update
and throws `OptimisticLockingFailureException` on conflict. This protects state transitions
(go-live, end, cancel) against concurrent modification — two operators cannot simultaneously
end the same stream.

### Domain State Machine on Entity

`StreamStatus` enum defines `allowedTransitions()` returning the legal target set.
`StreamSessionEntity` contains `transitionTo(StreamStatus target)` — validates the transition,
manages `startedAt`/`endedAt` timestamps, and throws on illegal transitions.
Convenience methods `goLive()`, `end()`, `cancel()` wrap `transitionTo()`.

This keeps lifecycle invariants inside the domain entity rather than scattered across
service methods. The service layer calls entity domain methods; the entity enforces rules.

### Flyway via JDBC Side-Channel

Flyway uses a separate blocking JDBC connection (`spring.flyway.url` / `spring.flyway.user` /
`spring.flyway.password`) targeting schema `stream`. The application's primary data access
is R2DBC — Flyway's JDBC connection is never used at runtime. This avoids pulling in
`spring-boot-starter-jdbc` as a primary `DataSource` bean.

## Third-Party Integration Catalog

How each external dependency is integrated into this service.

### PostgreSQL

| Aspect | Detail |
|--------|--------|
| **Role** | System of record — all stream sessions, categories, and metadata are durable here |
| **Schema** | `stream` — owned exclusively by this service; cross-schema FKs avoided |
| **Driver** | R2DBC (`r2dbc-postgresql`) — reactive, non-blocking |
| **Migrations** | Flyway via JDBC side-channel (V1–V7 so far; V7 is next: `broadcaster_username` + `broadcaster_verified`) |
| **Repositories** | `ReactiveCrudRepository` with derived query methods + custom `@Query` for ownership-scoped finds |
| **Entity pattern** | `Persistable<UUID>` + `@Transient isNew`, `@Version` optimistic locking |
| **Converters** | Custom `@WritingConverter`/`@ReadingConverter` pair for `StreamStatus` ↔ `String` |

### Kafka

| Aspect | Detail |
|--------|--------|
| **Role** | Outbound event publisher — notifies downstream services of stream lifecycle changes |
| **Topic** | `stream.control` — configured in `application.yml` (`spring.kafka.producer.*`) |
| **Pattern** | Reactive wrapper: `Mono.fromFuture(kafkaTemplate.send(...))` on `Schedulers.boundedElastic()` |
| **Integration** | Fire-and-forget from service layer — `.doOnSuccess(saved -> eventPublisher.publish(event).subscribe())` |
| **Delivery** | At-most-once — if Kafka is down, event is logged and lost; HTTP response succeeds regardless |
| **Envelope** | `StreamEvent` record with `eventId` (UUID, for consumer dedup), `eventType`, `streamId`, `timestamp`, `broadcasterSubject` |
| **Security** | Stream key hashes are **never** included in event payloads |
| **ADR** | [0001 — Stream State Machine](0001-stream-state-machine.md) (Kafka events mapped to transitions), [0002 — Kafka Event Publishing](0002-kafka-event-publishing.md) (reactive wrapper design) |

### SRS Media Server

| Aspect | Detail |
|--------|--------|
| **Role** | Media ingest — SRS receives RTMP from OBS, packages HLS for viewers |
| **Integration type** | Webhook receiver + JWT token issuer |
| **Publish tokens** | HS256 JWT issued by `PublishTokenService` — claims: `sub`, `streamId`, `srsName`, `exp`(now+2h). Embedded in RTMP URL as `?token={jwt}`. Self-validating (SRS passes token to webhook; stream-service validates it). |
| **Webhook endpoints** | `POST /v1/webhooks/srs/on_publish` → validates token → auto-goLive. `POST /v1/webhooks/srs/on_unpublish` → LIVE→ENDED. Unauthenticated in SecurityConfig (token provides auth). |
| **Validation (Sol3)** | DRAFT streams: token expiry enforced (gate for authorization). LIVE streams: expiry skipped (unbounded stream duration). |
| **Key storage** | Dual: `srs_name` (plaintext UUID, for URL reconstruction) + `stream_key_hash` (SHA-256, for SRS lookup) |
| **ADR** | [0004 — SRS Webhook Integration & Publish Token Architecture](0004-srs-webhook-publish-token.md) |

### Service Registry (Eureka)

| Aspect | Detail |
|--------|--------|
| **Role** | Client-side service discovery — registers with Eureka, resolved by gateway via `lb://stream-service` |
| **Library** | `spring-cloud-starter-netflix-eureka-client` |

## Security Model

### JWT Validation

The service is an OAuth2 Resource Server. `SecurityConfig` defines a reactive
`SecurityWebFilterChain` with a custom `ReactiveJwtDecoder` using Nimbus (HS256).
Accepts both `JWT` and `at+jwt` (RFC 9068) JOSE types.

### PBAC Enforcement

Authorization is enforced at the service layer (not the gateway). `StreamAuthorization.requireAccess(jwt, requiredAuthority)`
parses the JWT `ent` claim, matches resource patterns against the required `(domain, kind, action, ownerSubject)`,
and returns `Mono.empty()` (authorized) or `Mono.error(StreamAccessDeniedException)` (forbidden).

All PBAC classes (`AuthAction`, `AuthResourceDomain`, `AuthResourceKind`, `EntitlementMatcher`)
are marked `PBAC-COMMON-CANDIDATE` — they mirror auth-service enums and will be extracted
to a shared `pbac-common` library in Phase 6.2.

### Error Codes

`StreamAuthenticationEntryPoint` returns structured JSON 401 with distinguishable `error_code`:
- `token_expired` — JWT is valid but expired (client should refresh)
- `invalid_token` — JWT is malformed, wrong signature, or unknown issuer

## Forward-Looking Sketch

What's planned but not yet built. See [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) for the
authoritative phase checklist.

| Item | Phase | Description |
|------|-------|-------------|
| Kafka integration testing | 2.6 | Testcontainers-based Kafka producer tests |
| Schedule reminder batch | 2.7 | Pre-stream reminders for scheduled broadcasts (depends on Phase 5 notifications) |
| Stream templates | 2.8 | Reusable boilerplates for streamers who go live regularly |
| SRS snapshot thumbnails | 2.9 | Auto-generate stream thumbnails from live feed via SRS/ffmpeg (depends on Phase 4.0 SRS) |
| Authenticated channel read | A4 | `GET /v1/channels/{username}` — safe cross-user projection for viewer-facing channel page |
| Channel page shell | B1–B4 | Angular `/@username` page with session rail, category strip, owner mode |
| `pbac-common` extraction | 6.2 | Extract duplicated PBAC classes into a shared Gradle module |
| `channel-service` extraction | Future | Lift channel read + social graph + playlists into a dedicated service (see [ADR-0007](0007-public-channel-read-and-channel-service-seam.md)) |

## References

- [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — per-service architectural style recommendations
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — authorization model and JWT claims design
- [IMPLEMENTATION-PLAN.md](../../IMPLEMENTATION-PLAN.md) — master phase plan; Phase 2 checklist
- [ADR-0001](0001-stream-state-machine.md) — stream state machine with entity domain methods
- [ADR-0002](0002-kafka-event-publishing.md) — reactive Kafka publisher design
- [ADR-0003](0003-categories-tags.md) — managed category vocabulary + `TEXT[]` tags
- [ADR-0004](0004-srs-webhook-publish-token.md) — SRS webhook + JWT publish token architecture
- [ADR-0005](0005-stream-thumbnails.md) — SRS auto-snapshot thumbnails (Proposed)
- [ADR-0007](0007-public-channel-read-and-channel-service-seam.md) — authenticated channel read + extraction seam
- [stream-service source](../../../main/source/backend/stream-service/src/main/java/com/streaming/stream/)
