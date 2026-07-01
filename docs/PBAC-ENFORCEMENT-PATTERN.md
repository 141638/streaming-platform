# PBAC Enforcement Pattern

**Status:** Implemented (in stream-service) — replicable to chat, notification, and future services
**Source:** [EntitlementMatcher.java](../main/source/backend/stream-service/src/main/java/com/streaming/stream/security/EntitlementMatcher.java), [StreamAuthorization.java](../main/source/backend/stream-service/src/main/java/com/streaming/stream/security/StreamAuthorization.java)

## Problem

A service needs to enforce per-resource authorization without calling back to the auth service on every request. The JWT already carries materialized entitlements in the `ent` claim — but no downstream service reads them.

The enforcement must:
1. Work in a reactive (WebFlux) pipeline without blocking
2. Handle ownership-based access (`self` scope — "you can only read your own streams")
3. Support wildcard access (`*` scope — admin can read all streams)
4. Be testable as pure logic (no framework magic)

## Pattern

### Two-layer design

```
┌─────────────────────────────┐
│     EntitlementMatcher       │  ← pure logic, no Spring, no reactive types
│  isAuthorized(jwt, domain,   │
│    kind, action, ownerSub)   │
│  → boolean                   │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│     StreamAuthorization       │  ← @Component, wraps in Mono<Void>
│  requireAccess(jwt, domain,  │
│    kind, action, ownerSub)   │
│  → Mono<Void>                │
└─────────────────────────────┘
```

- **`EntitlementMatcher`** is a stateless utility class — pure function, easily unit-tested with parameterized inputs
- **`StreamAuthorization`** is a Spring `@Component` that wraps the matcher in `Mono<Void>` for clean chaining in reactive pipelines

### Entitlement line format

```
allow stream:session:self create read update lifecycle issue_key
└──┬──┘ └──────┬──────┘└─┬─┘ └──────────────┬──────────────────┘
effect  resource    scope       actions
         pattern
```

The `ent` claim in the JWT is a `List<String>` of these lines. Each line grants `allow` on a resource pattern for one or more actions.

### Matching algorithm

```
1. Extract ent[] from JWT claims → empty list = deny
2. For each line starting with "allow ":
   a. Parse: resourcePattern, actions...
   b. Check resourcePattern starts with "{domain}:{kind}:"
   c. Extract scope (self, *, or specific UUID)
   d. Resolve scope:
      - "self" → ownerSubject != null && ownerSubject == jwt.sub
      - "*"    → always pass
      - UUID   → ownerSubject == scope
   e. Check requested action ∈ action list
   f. First match → allow
3. No match → deny
```

Only `allow` lines are materialized into the JWT (`deny` statements are skipped by `EntitlementLinesMaterializer`). No match = deny by default.

### Scope resolution

| Scope | Meaning | Check |
|-------|---------|-------|
| `self` | The caller's own resources | `entity.broadcasterSubject == jwt.sub` |
| `*` | All resources (admin) | Always passes |
| `<uuid>` | A specific resource instance | `entity.broadcasterSubject == scope` |

## Usage in Service Layer

Insert the authorization check after loading the entity, before business logic or response mapping:

```java
@Service
@RequiredArgsConstructor
public class StreamService {

    private final StreamSessionRepository repository;
    private final StreamAuthorization authorization;

    public Mono<StreamResponse> getStream(UUID id, Jwt jwt) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new StreamNotFoundException(id)))
            // ── PBAC check ──────────────────────────────────────
            .flatMap(entity -> authorization
                .requireAccess(jwt, "stream", "session", "read",
                    entity.getBroadcasterSubject())
                .thenReturn(entity))
            // ─────────────────────────────────────────────────────
            .map(StreamResponse::from);
    }
}
```

## Error Response: 404 vs Exception

| Method | Denial Strategy | Rationale |
|--------|----------------|-----------|
| `getStream`, `getPublishKey` | **404 Not Found** via `StreamNotFoundException` | Don't leak that someone else's stream exists |
| `updateStream`, `deleteStream`, `issuePublishKey` | **`StreamAccessDeniedException`** | Caller already knows the stream exists (they navigated to it) |

The `getStream` method maps `StreamAccessDeniedException` → `StreamNotFoundException` using `onErrorMap`:

```java
.flatMap(entity -> authorization
    .requireAccess(jwt, "stream", "session", "read", entity.getBroadcasterSubject())
    .onErrorMap(StreamAuthorization.StreamAccessDeniedException.class,
            e -> new StreamNotFoundException(id))
    .thenReturn(entity))
```

## Controller Changes

The controller extracts `Jwt` via `@AuthenticationPrincipal` and passes it to the service:

```java
@GetMapping("/streams/{id}")
public Mono<ResponseEntity<StreamResponse>> get(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id) {
    return streamService.getStream(id, jwt)
            .map(ResponseEntity::ok);
}
```

Endpoints that already filter by `sub` (like `listMyStreams`) don't need additional checks — `findAllByBroadcasterSubject(sub)` already scopes the result.

## Action Mapping

Each endpoint maps to a PBAC action:

| HTTP Method | Endpoint | PBAC Action |
|-------------|----------|------------|
| POST | `/streams` | `create` (new — no ownership check) |
| GET | `/streams` | `read` (filtered by `sub`) |
| GET | `/streams/{id}` | `read` |
| PATCH | `/streams/{id}` | `update` |
| DELETE | `/streams/{id}` | `delete` |
| POST | `/streams/{id}/publish-key` | `issue_key` |
| GET | `/streams/{id}/publish-key` | `read` |

## Playbook: Adding PBAC to a New Service

1. **Create `EntitlementMatcher`** — copy from stream-service or extract to shared library (Phase 6.2). The class is self-contained with no service-specific logic.

2. **Create `{Service}Authorization`** — `@Component` wrapping `EntitlementMatcher` with `requireAccess()` → `Mono<Void>`. Include a service-specific `AccessDeniedException`.

3. **Wire into service layer** — inject via constructor, add `Jwt jwt` param to methods, insert `.flatMap(entity -> authorization.requireAccess(...).thenReturn(entity))` after entity load.

4. **Update controller** — add `@AuthenticationPrincipal Jwt jwt` to endpoints, pass to service.

5. **Map actions** — document which endpoints use which PBAC actions. The action names come from the [PBAC action catalog](PBAC-AUTHORIZATION.md).

6. **Write tests** — `EntitlementMatcherTest` (pure logic, no mocking needed) + `{Service}Test` (mock authorization, verify deny scenarios).

## Related Docs

- [PBAC-AUTHORIZATION.md](PBAC-AUTHORIZATION.md) — Full PBAC model, JWT design, action catalog, resource types
- [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) — Phase roadmap

## References

- [Spring Security: Reactive JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html)
- [Project Reactor: Testing with StepVerifier](https://projectreactor.io/docs/core/release/reference/#testing)
