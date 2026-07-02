# ADR-0003: JWT-Derived Author Identity for Chat Messages

**Date**: 2026-07-02
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

The chat service needs to associate every message with its author. The prototype controller accepted an `author` field in the request body — meaning any authenticated client could send a message claiming to be from any user. This is an impersonation vulnerability.

The platform uses JWT-based authentication with the PBAC model described in [`docs/PBAC-AUTHORIZATION.md`](../../PBAC-AUTHORIZATION.md). Each request carries a signed JWT whose `sub` claim identifies the authenticated user. The chat service already validates this JWT in `SecurityConfig` — the question is how to use it for author identity.

Options: (1) trust the request body `author` field (prototype — vulnerable), (2) extract `sub` from JWT in the controller and pass it down, (3) extract `sub` in a Spring Security filter and inject it as a context object.

## Decision

We derive the author identity exclusively from the **JWT `sub` claim**, extracted at the controller layer via `@AuthenticationPrincipal Jwt jwt` and passed to the application layer as a plain `String authorSubject`.

Specifically:
- `SendMessageRequest` is a record with a single field: `@NotBlank String content`. There is **no author field** in the request body.
- `ChatController.sendMessage()` reads `jwt.getSubject()` and passes it to `ChatService.sendMessage(roomKey, authorSubject, body)`.
- `ChatMessage.create()` accepts `authorSubject` as a parameter and stores it in the `author_subject` column.
- If a client sends an `author` field in the JSON body, Spring ignores it (Jackson deserialization into `SendMessageRequest` drops unknown properties).

## Alternatives Considered

### Alternative 1: Trust the request body (prototype approach)
- **Pros**: Simplest code — no JWT interaction needed in the controller.
- **Cons**: Any authenticated user can impersonate any other user. This is not a theoretical risk — the `author` field in the prototype was a plain string with no server-side validation.
- **Why not**: Security vulnerability. Rejected outright.

### Alternative 2: Spring Security filter + SecurityContext
- **Pros**: Cross-cutting — every controller method automatically has access to the authenticated user without repeating `@AuthenticationPrincipal Jwt jwt` in every method signature. Cleaner controller code.
- **Cons**: Magic. A developer reading `ChatController` doesn't see where `authorSubject` comes from without tracing through security configuration. The stream-service already uses `@AuthenticationPrincipal` explicitly — consistency matters.
- **Why not**: The explicit approach is one parameter per method. At 2 endpoints, the boilerplate is trivial. If the service grows to 20 endpoints, extract a `@AuthenticatedSubject` annotation or a base controller — but don't solve a scaling problem that doesn't exist yet.

### Alternative 3: Custom `@AuthenticatedSubject` annotation with `HandlerMethodArgumentResolver`
- **Pros**: Combines explicitness (annotation is visible in method signature) with conciseness (no `jwt.getSubject()` call in every method).
- **Cons**: Custom Spring infrastructure for a single claim extraction. Adds a class that every new team member must discover and understand. The stream-service doesn't use this pattern.
- **Why not**: Over-engineering for `jwt.getSubject()`. If we need more claims (`ent`, `attr`, `pv`), the planned `StreamingAccessTokenPayload.from(Jwt)` in `pbac-common` ([PBAC-AUTHORIZATION.md §12.3](../../PBAC-AUTHORIZATION.md#123-jwt-payload-types-share-for-type-safety)) solves claim access cleanly without custom resolvers.

## Consequences

### Positive
- **No impersonation**: Author identity is cryptographically bound to the JWT. A user cannot send a message as someone else without possessing that user's signed token.
- **Auditability**: Every message's `author_subject` matches a `sub` claim in the auth service's token issuance log. End-to-end traceability from message to login event.
- **Consistent with PBAC**: The PBAC model reserves `chat:message:room:{key}` with action `send`. When per-service `ent` enforcement is implemented, the authorization check will naturally verify that `sub` is allowed to send in that room — the identity is already correct.
- **Self-documenting**: A developer reading `ChatController` sees `@AuthenticationPrincipal Jwt jwt` and immediately knows the identity source.

### Negative
- **No bot/system message support (yet)**: If we introduce system-generated messages (e.g., "Streamer has started broadcasting"), the `author_subject` must be a synthetic principal. **Mitigation**: the column is a plain string, so `system:chat-service` or a special service-account `sub` works without schema changes.
- **Verbose test setup**: Every `@WebFluxTest` for `ChatController` must include a mock JWT with a `sub` claim. **Mitigation**: this is one `Jwt.withTokenValue("...").header("alg", "HS256").claim("sub", "user-1").build()` call — acceptable overhead.

### Risks
- **JWT `sub` vs. display name mismatch**: The `sub` claim is an opaque user identifier, not a human-readable display name. A client rendering chat messages needs to resolve `sub` → display name. **Mitigation**: this is a client concern. If we want to avoid N+1 user lookups, the gateway or a future chat-join response can include a `participants` map (`sub → displayName`). The chat service itself does not resolve identities — it stores and returns `author_subject` as-is.
- **Token expiry during long chat sessions**: A viewer's access token expires after 15 minutes. The chat service only checks JWT validity at request time — sending a message with an expired token fails with 401. **Mitigation**: refresh-token rotation is already implemented in auth-service. The SPA refreshes proactively before expiry.
