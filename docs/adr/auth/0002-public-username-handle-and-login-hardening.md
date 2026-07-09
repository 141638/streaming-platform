# ADR-0002: Authenticated-Visible Username Handle — JWT Username Claim and Login Enumeration Fix

**Date**: 2026-07-09
**Status**: proposed
**Deciders**: streaming-platform team

## Context

The channel redesign replaces the streamer-only `/dashboard/streams` view with a
Twitch-style channel page reachable at a per-user handle URL (`/@username`). The whole
platform stays **behind login** — channels are visible to *authenticated users*, not to the
open internet (guest/public access is a deliberately deferred decision; see below). This
still makes the **username a first-class in-app identifier** for the first time: any
logged-in user can see any other user's handle.

Today the username is *also the login identifier*: `LoginRequest(username, password)` and
`AuthService.authenticate()` resolves the account via `findByUsernameAndDeleteFlagFalse(...)`.
Email is only used in the password-reset flow. Surfacing the username in-app therefore
exposes (to authenticated peers) the exact string an attacker needs for the account half of
a credential-guessing attack.

Two related facts sharpen the risk:

1. **Username enumeration is already possible.** The login path throws
   `UserAccountNotFoundException` (404) for an unknown user, distinguishable from an
   invalid-password error (`InvalidCredentialsException`, 401). An attacker can probe which
   usernames exist purely from the auth endpoint — independent of the channel page.
2. **There is no throttle on the guess path.** auth-service has no login rate-limiting. That
   throttling concern is split into its own decision ([ADR-0003](0003-login-rate-limiting.md))
   so this ADR stays focused on the claim + the enumeration-oracle fix.

Separately, other services cannot currently render channel chrome (display name, verified
badge) because the JWT carries only the user UUID in `sub` — no `username` claim. The
stream-service denormalization decision ([stream/ADR-0007](../stream/0007-public-channel-read-and-channel-service-seam.md))
depends on the username being available in the token at stream-create time.

The industry norm (Twitch, GitHub, YouTube `@handles`) is **not to hide the username** but
to make knowing it irrelevant to account safety: the username is an identifier, the password
is the only secret, and the password *path* is protected.

## Decision

**Add a `username` claim to the access JWT so services can render and denormalize channel
identity, and close the login enumeration oracle by returning a single uniform 401.**
Brute-force throttling is a companion decision ([ADR-0003](0003-login-rate-limiting.md)),
not part of this ADR.

Because channel access is **authenticated-only**, exposing the username is not a public
publication — there is no gateway allowlist or `permitAll` needed for it. The residual risk
is peer-visible enumeration, addressed below.

### 1. `username` in the JWT `attr` claim

Add a catalog-driven `username` attribute to the existing `attr` map, alongside `roles`,
`tier`, and `verified_streamer`. Implementation mirrors the current resolver:

- `SubjectAttributeResolver` gains a `KEY_USERNAME` case returning `user.getUsername()`.
- The `catalog_subject_attribute` table gets a `username` row so the resolver emits it
  (Flyway V9).
- `StreamingAccessTokenPayload` / `SubjectAttributes` gain a `username` field.

The token continues to key identity on the **immutable UUID** (`sub`); `username` is a
**mutable convenience claim**. Consumers that need an authority for ownership use `sub`;
consumers that need a display/handle use `attr.username`, accepting that a renamed user
carries a stale handle until the token refreshes (bounded by the access-token TTL).

### 2. Uniform login error (enumeration-oracle fix)

Collapse the 404-vs-401 split: `AuthService.authenticate` throws
`InvalidCredentialsException` (401, "Invalid user credentials") for **both** the
unknown-user case (empty `Optional`) and the password-mismatch case, so the auth endpoint no
longer confirms which usernames exist. (Grep for other callers of
`UserAccountNotFoundException` before repurposing the empty-`Optional` path; keep that
exception for genuinely internal not-found uses.)

### Deliberately deferred (documented, not built now)

- **Guest / public (unauthenticated) channel access** — the platform is login-gated for now.
  Opening channels to the open internet (with the SEO/discovery upside and the guest-exposure
  tradeoff) is explicitly revisitable in a later phase, and would get its own ADR.
- **Login rate-limiting / lockout** — its own decision, [ADR-0003](0003-login-rate-limiting.md)
  (being built alongside this, but recorded separately).
- **Login by email OR username** — cheap decoupling so the handle is not the *only* login
  path. Recommended next; not required here.
- **A distinct user-changeable public handle/slug** separate from the login identifier — the
  clean long-term separation. Deferred until it earns its complexity.
- **MFA and breached-password (HIBP) checks** — future hardening, not blockers.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Keep username private; expose an opaque channel id | Rejected | Defeats the product goal (shareable, human `/@handle` URLs) and matches no comparable platform. |
| Open channels to guests now | Deferred | Discovery upside, but widens username exposure to the open internet and adds gateway/security work; not needed for a login-gated pet project. Revisit with its own ADR. |
| Keep the 404-vs-401 split | Rejected | It's an active enumeration oracle on a public endpoint, independent of any channel page. |
| Put `username` in a top-level claim instead of `attr` | Rejected | `attr` is the established subject-attribute channel already consumed by services; a one-off top-level claim fragments the contract. |
| Separate public handle from login username **now** | Deferred | Correct end state but adds a schema/migration + UX for renames before there is pressure; email-login is the cheaper interim decoupling. |

## Consequences

- **Positive**: Enables `/@username` channel URLs for authenticated users. Downstream services
  render and denormalize channel identity from the token with no extra lookup. The auth
  endpoint stops leaking account existence. No gateway/permitAll changes (channels stay
  authenticated), so a smaller, safer surface than a public rollout.
- **Negative**: Username is peer-visible and reusable as the account identifier for credential
  stuffing — mitigated by throttling ([ADR-0003](0003-login-rate-limiting.md)); residual risk
  is the industry-standard baseline. The `attr.username` claim is stale between a rename and
  the next token refresh.
- **Risks**:
  - *Enumeration still possible via authenticated channel browsing.* Accepted for a
    login-gated service; the mitigation is on the password path (ADR-0003), not on hiding the
    handle.
  - *Rename staleness.* The claim lags a rename until token refresh; acceptable within TTL.

## References

- Related ADRs: [ADR-0001: Redis-Based Refresh Token Storage](0001-redis-refresh-token-storage.md)
  (Redis infra precedent); [ADR-0003: Login Rate Limiting](0003-login-rate-limiting.md)
  (the throttling companion to this ADR's enumeration fix); [stream/ADR-0007: Authenticated
  Channel Read & channel-service Seam](../stream/0007-public-channel-read-and-channel-service-seam.md)
  (consumes the `username` claim via denormalization)
- Source files: `SubjectAttributeResolver.java`, `AccessTokenIssuanceService.java`,
  `StreamingAccessTokenPayload.java`, `SubjectAttributes.java`, `AuthService.java`,
  `UserAccountNotFoundException.java`, `InvalidCredentialsException.java`,
  `catalog_subject_attribute` (Flyway V9)
- External docs: [PBAC-AUTHORIZATION.md](../../PBAC-AUTHORIZATION.md) — subject-attribute model
