# ADR-0005: Moderation Stays a Chat Kind — `moderation` Domain is Condition-Triggered, Not Phase-Deferred

**Date**: 2026-07-11
**Status**: accepted
**Deciders**: hieuht, Claude

## Context

When flattening the chat PBAC grammar ([ADR-0004](0004-two-layer-chat-authorization.md) grammar update), we had to pick a canonical shape for the moderation authority. Two structures were on the table:

- **M1 — moderation as a `kind` under the `chat` domain**: `chat:moderation:self moderate`, `chat:moderation:* moderate`. Matches the `{domain}:{kind}:{scope}` shape every other service already uses (`stream:session:self`, `stream:publish-key:self`).
- **M2 — moderation as its own first-class domain**: `moderation:chat-room:self moderate`, where the target resource type becomes the kind (hyphenated, so it stays a single `:`-segment — precedent: `stream:publish-key`). This groups moderation as a cross-cutting capability that could later span multiple resource types and carry granular verbs (`ban` / `mute` / `delete_message` / `timeout`).

The pull toward M2 is real: moderation *feels* cross-cutting. But the flexibility usually cited for it — "a promoted moderator holds moderation authority on multiple specific resources" — is **already achievable under M1** via instance scope (`chat:moderation:{ownerSubject} moderate`). And the deeper limitation for true per-room moderator grants (the matcher resolves instance scope against `room.broadcasterSubject`, not the room key, so a specific grant covers *all of an owner's rooms*) is **identical under both structures** — it is solved by threading `roomKey` into `RequiredAuthority`, not by renaming the domain.

## Decision

**Adopt M1.** Moderation is a `kind` under the `chat` domain: `chat:moderation:self` (streamer, own room) and `chat:moderation:*` (staff). No new `AuthResourceDomain`, no cross-domain kind, no extra migration surface. This fully unblocks live PBAC enforcement and keeps the matcher identical to stream-service's for the Phase 6.2 `pbac-common` extraction.

**M2 is recorded here as a condition-triggered alternative — explicitly NOT a phase-deferred TODO.** We do not schedule M2 for "a later phase." We adopt it **only if and when** a concrete maintainability condition is met (see below). Until then, the default posture is a deliberate *bias against* implementing it: absent the trigger, M1 is the answer, and "we'll want it eventually" is not a sufficient reason to build it.

### Adoption trigger (the only condition that justifies M2)

Reconsider M2 **only when moderation business logic becomes genuinely hard to maintain as a per-domain kind** — concretely, when **either** of these is true:

1. **Cross-resource moderation exists**: moderation must be expressed over more than one resource domain (e.g. moderate chat rooms *and* stream sessions *and* user profiles) and duplicating a `:moderation` kind into each domain becomes the source of drift and inconsistency.
2. **Granular moderation verbs exist**: a single coarse `moderate` action no longer models the domain, and distinct verbs (`ban`, `mute`, `delete_message`, `timeout`, `unban`) need independent grant/audit — at which point a `moderation` domain with varied actions is the clean home.

If neither holds, do not implement M2 — even if a new moderation feature ships. New moderation features that fit within `chat:moderation:{scope}` are M1 work, not M2 triggers.

## Alternatives Considered

### M2 now (moderation as a first-class domain)
- **Pros**: first-class cross-cutting capability; natural home for granular verbs; riding the current grammar migration + token reissue is cheaper than a second migration later *if* M2 is imminent.
- **Cons**: larger diff across auth-service and chat-service (new `AuthResourceDomain`, new hyphenated kind, seed rewrite, `ModerationService` call-site change, docs) for flexibility that instance scope already provides; `moderation:...:... moderate` is redundant while the action stays coarse; the real per-room-grant limitation is orthogonal to it.
- **Why not now**: the justifying conditions do not hold today (chat is the only moderated domain; `moderate` is the only verb). Building M2 now is speculative generality (YAGNI).

## Consequences

### Positive
- Live enforcement unblocked with minimal surface; matcher stays shareable for 6.2.
- The expensive-to-retrofit concern (grammar migration + platform-wide token reissue) is acknowledged and pre-answered: when a trigger fires, M2's migration is a known, bounded piece of work — not a surprise.

### Negative
- If a trigger fires later, M2 will require its own grammar migration and token-refresh window. Accepted: we trade a possible future migration for not building speculative structure now.

### Risks
- **Trigger creep**: teams may reach for M2 for a new moderation feature that actually fits M1. Mitigation: the trigger is written narrowly above — cross-resource or granular-verb, nothing softer.

## References

- [ADR-0004](0004-two-layer-chat-authorization.md) — two-layer chat authorization + grammar flatten (V10)
- [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — §2 resource types, §3 action catalog
- `auth-service/.../db/migration/V10__flatten_chat_pbac_grammar.sql`
- `chat-service/.../security/EntitlementMatcher.java`, `RequiredAuthority.java`
