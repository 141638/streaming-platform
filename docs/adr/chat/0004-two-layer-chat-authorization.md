# ADR-0004: Two-Layer Chat Authorization — PBAC Capability + Resource-State Moderation

**Date**: 2026-07-11
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

Phase 3.4 introduces authorization to the chat service. Two distinct questions must be answered on every request, and they have fundamentally different lifecycles:

1. **Capability** — "Does this principal hold the `send` / `read` / `moderate` capability at all?" This is coarse, role-derived, and stable for a session.
2. **Resource-state** — "Is this specific user blocked from *this* room *right now*?" (a ban) This is fine-grained, per-`(user × room)`, time-bound (`expires_at`), high-churn, and must take effect **immediately** — mid-session, without a token refresh.

The platform's PBAC model ([PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md)) carries capabilities in the JWT `ent` claim. stream-service already has an `EntitlementMatcher` for this, flagged `PBAC-COMMON-CANDIDATE` for a Phase 6.2 `pbac-common` extraction. The open question was where **bans** belong: in the token (as deny-policies) or in the service (as resource state).

## Decision

Chat authorization is **two layers**:

- **Layer 1 — PBAC capability** (`ent` in JWT, in-memory): a `com.streaming.chat.security` package ported from stream-service. `ChatAuthorization.requireAccess(jwt, RequiredAuthority)` guards each endpoint. Owner scope resolves against `room.broadcasterSubject` (`self`/`*`/literal). Enforcement is **enabled by default** (`chat.pbac.enabled=true`); the `dev` Spring profile allows disabling for local testing. In non-dev profiles, disabling PBAC throws `IllegalStateException` at startup (hard-fail guard, added Phase 6.2).
- **Layer 2 — ban** (resource-state, `chat_ban` table): `BanSendGuard` plugged into the `SendGuard` seam checks the DB before persisting a message. Bans are **not** policies-in-token.

Bans return `403 CHAT_USER_BANNED`; PBAC denials return `403 AUTHZ_DENIED` — distinct envelope `code`s so clients disambiguate without abusing the status class.

### Grammar update (auth V10, 2026-07-11)

The original seed emitted 4-segment chat resources (`chat:message:room:*`) that the ported matcher could not parse (see Risks, since resolved). Migration `V10__flatten_chat_pbac_grammar.sql` flattens chat to the canonical 3-segment `{domain}:{kind}:{scope}` used by every other service:

| Concern | Resource line |
|---------|---------------|
| Room metadata | `chat:room:* read` |
| Message content (recent + durable) | `chat:message:* read send read_history` |
| Streamer self-moderation | `chat:moderation:self moderate` |
| Staff moderation | `chat:moderation:* moderate` |

Two accompanying decisions: message reads are **unified under `chat:message`** (`read` = recent/hot path, `read_history` = durable cursor page) with `chat:room` retained only for room-metadata lookups; and moderation stays a **kind under the `chat` domain** rather than a first-class `moderation` domain — the latter is a condition-triggered alternative recorded in [ADR-0005](0005-moderation-domain-condition-triggered.md).

## Alternatives Considered

### Alternative 1: Bans as deny-policies in the JWT `ent` claim
- **Pros**: single authorization mechanism; no per-request DB read for bans.
- **Cons**: a JWT is a bearer credential valid until expiry — it cannot be revoked mid-session, so a banned user keeps posting until refresh (or forces a server-side revocation list, which reintroduces the per-request lookup it was meant to avoid). The matcher is allow-only; deny-semantics force deny-overrides-allow precedence it doesn't implement. Per-`(user × room)` negative policies grow unbounded and bloat every token.
- **Why not**: moderation must be instant and revocable; the token is the wrong place for high-churn, time-bound, resource-specific state. This is the exact PBAC-in-token revocation trade-off in [PBAC-AUTHORIZATION.md §9](../../PBAC-AUTHORIZATION.md).

### Alternative 2: Ban check as pure business logic inline in `ChatService.sendMessage`
- **Pros**: no new abstraction.
- **Cons**: entangles the Layer-2 check with cache/persistence orchestration; the send pipeline was also being extended with PBAC and evict-on-write-failure in the same window (parallel tracks).
- **Why not**: the `SendGuard` seam (Phase 0) keeps the resource-state check swappable and testable in isolation, and let the moderation track and the cache track land without editing the same lines.

### Alternative 3: Enforce PBAC live immediately (no dark-launch flag)
- **Pros**: simplest; no flag to remember to flip.
- **Cons (at the time)**: auth-service's seeded `ent` grammar was not yet compatible with the ported matcher — enabling live would deny `send`/`read_history`/`moderate` platform-wide.
- **Resolution (Phase 6.2)**: The grammar mismatch was resolved (auth V10). Dark-launch served its purpose — the wiring landed safely, the grammar was reconciled, and PBAC was enabled by default with a hard-fail guard in non-dev profiles. The feature flag remains for dev-profile testing only.

## Consequences

### Positive
- **Instant, revocable moderation**: a ban takes effect on the banned user's next `sendMessage` (DB read), with no token dependency.
- **Right layer for each concern**: capabilities stay in the stable token; volatile state stays in the service — the "PBAC in token + resource-level ABAC in service" model [PBAC §"combines"](../../PBAC-AUTHORIZATION.md) recommends for chat.
- **Clean 6.2 extraction path**: the entire `security/` package is `PBAC-COMMON-CANDIDATE`-marked; removal = delete the package + the `requireAccess(...)` call sites.
- **Unambiguous client contract**: `AUTHZ_DENIED` vs `CHAT_USER_BANNED` distinguished by `code`, both `403`.

### Negative
- **Per-send DB read for the ban check**: PG-direct, single indexed lookup on the already-warm connection. A Redis ban cache is deferred (`OPT(scale)` marker in `BanSendGuard`).
- **Two enforcement points to reason about** (controller/service PBAC + guard ban) rather than one.
- **PBAC is active by default** — a startup hard-fail in non-dev profiles prevents accidentally running with enforcement disabled in production. The dev profile still allows disabling for local testing with a visible WARN.

### Risks
- **PBAC `ent` grammar mismatch (blocks enabling)** — ~~auth-service seeds 4-segment chat resources~~ **RESOLVED (auth V10, 2026-07-11)**. The mismatch: auth seeded `chat:message:room:*` / `chat:moderation:room:*`, but the ported matcher parses `domain:kind:` as a 2-segment prefix and treats the remainder (`room:*`) as one scope token — so `read` (`chat:room:*`) matched, but `send` / `read_history` / `moderate` never did. Resolved by option (a) from the original mitigation: `V10__flatten_chat_pbac_grammar.sql` flattens the seed to 3-segment resources (`chat:message:*`, `chat:moderation:self|*`) and closes the coverage gaps (`policy.viewer.base` gained `send`/`read_history`; `policy.streamer.live` gained `chat:moderation:self moderate`). The matcher is unchanged, so it stays byte-identical to stream-service's for the Phase 6.2 `pbac-common` extraction. **Phase 6.2**: Enforcement enabled by default (`chat.pbac.enabled=true`) with a hard-fail guard in non-dev profiles.
- **Ban read latency at scale**: bounded by index + tiny per-room cardinality now; Redis ban cache is the documented upgrade.
- **TOCTOU between ban check and persist**: sub-millisecond same-scheduler window; acceptable for real-time chat (no distributed lock).

## References

- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — §6.3 chat mapping, §7 per-service evaluation, §9 revocation trade-offs
- [ADR-0002](0002-jwt-derived-author-identity.md) — JWT `sub` as author identity (Layer-1 identity foundation)
- [ADR-0000](0000-architecture-foundation.md) — chat architecture; `PBAC-common` extraction note
- stream-service `security/EntitlementMatcher` — the ported precedent (`PBAC-COMMON-CANDIDATE`)
- `chat-service/.../security/ChatAuthorization.java`, `application/BanSendGuard.java`, `application/SendGuard.java`
- [chat-3.4-3.6-blueprint.md](../../plans/chat-3.4-3.6-blueprint.md), [chat-3.4-3.6-retrospective.md](../../plans/chat-3.4-3.6-retrospective.md)
