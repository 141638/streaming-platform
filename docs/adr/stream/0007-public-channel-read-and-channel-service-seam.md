# ADR-0007: Authenticated Channel Read in Stream-Service, with a channel-service Extraction Seam

**Status:** Accepted
**Date:** 2026-07-09
**Accepted:** 2026-07-10
**Domain:** Stream Service

## Context

The channel redesign introduces a Twitch-style channel page at `/@username`, visible to
**authenticated users** (the platform is login-gated; guest/public access is deferred — see
[auth/ADR-0002](../auth/0002-public-username-handle-and-login-hardening.md)). It must render,
for any logged-in visitor viewing any channel:

- channel identity chrome — display name and a **verified** badge;
- a rail of the broadcaster's recent stream sessions;
- a strip of recently-streamed categories;
- an **About tab** with the broadcaster's **bio**, **social links** (platform + URL pairs),
  and **derived channel stats** (total streams, hours streamed, top category, streaming-since
  date, per-category breakdown);
- placeholders for social features (follower/subscriber counts, Follow / Subscribe / Gift)
  and for playlists.

None of the social or playlist domains exist today. Followers, user→user subscriptions,
gifting, and playlists have **no entity, table, or DTO** anywhere in the backend. What does
exist is what the page mostly needs *now*: `stream_session` (owned via
`broadcaster_subject`, a String holding the JWT `sub` UUID) and `stream_category`.

Two structural facts constrain the design:

1. **stream-service knows only UUIDs.** `broadcaster_subject` stores the user UUID string.
   There is no username anywhere in stream-service and no username↔UUID resolver — auth-service
   owns usernames and exposes no profile endpoint. Serving `/@username` requires the
   username to be *reachable* from the stream read path.
2. **A "channel" is a distinct bounded context.** The social graph (followers, subscriptions,
   gifting) and curation (playlists) are neither stream lifecycle (DRAFT→LIVE→ENDED, publish
   keys, SRS) nor authN/authZ (credentials, PBAC, tokens). Long term it deserves its own
   service.

Because the page is **authenticated but not owner-scoped** — user A can view user B's
channel — the read must return a projection that is safe to hand any authenticated peer:
never another user's `broadcaster_subject`, publish key, or RTMP URL. This is the sharpest
correctness requirement of the endpoint.

The question this ADR settles: **where does channel read logic live, and how do we serve
`/@username` without premature infrastructure?** This round builds shells only
(see [IMPLEMENTATION-PLAN Phase B]); followers/subscriptions/gifting/playlists are later phases.

## Decision

**Serve the authenticated channel read from stream-service for now, backed by a denormalized
broadcaster identity on `stream_session`. Do not create a channel-service yet. Establish a
clean extraction seam so the social/playlist phase can lift channel logic into a dedicated
`channel-service` with minimal cost.**

### 1. Denormalize broadcaster identity onto `stream_session`

Add two nullable, client-read-only columns (Flyway V7, schema `stream`):

- `broadcaster_username VARCHAR(128)`
- `broadcaster_verified BOOLEAN`

Both are populated at **stream-create time from the JWT `attr` claim**
(`attr.username`, `attr.verified_streamer` — see [auth/ADR-0002](../auth/0002-public-username-handle-and-login-hardening.md)),
never from the request body. Existing rows created before the `username` claim existed are
left NULL and rendered with a placeholder handle (no online backfill source without a
username↔UUID lookup).

This is the same cost-split pattern as [ADR-0005](0005-stream-thumbnails.md): a cheap field
addition now, deferring the expensive capability (a real user/channel domain).

### 2. Authenticated read endpoint (stream-service, for now)

`GET /v1/channels/{username}` returns a **safe cross-user projection**:

- **Includes:** display name, verified flag, public sessions (rail), recent categories.
- **Excludes, enforced server-side:** `broadcaster_subject`, publish key, `rtmpUrl`, and any
  lifecycle-control affordance. Since any authenticated user can call this for any handle,
  these fields must never appear in the response — this is a hard requirement, not a UI
  concern. The existing owner control panel (`/channel/:id`, publish key + lifecycle) stays
  owner-only and separate.

The endpoint stays under the service's default `anyExchange().authenticated()` — **no
`permitAll` matcher and no gateway allowlist**, because channels are login-gated. Owner-vs-
visitor affordances are decided **client-side** by comparing `attr.username` from the caller's
token to the route handle; the server never trusts a client claim of ownership for control
surfaces (those remain UUID/`sub`-authorized as today).

### 3. Extraction seam

The denormalized columns and the `/v1/channels/{username}` handler are written as a
self-contained read slice so that, at the social/playlist phase, they move to a new
`channel-service` following the platform's standard five-step service lifecycle (Eureka
registration, gateway route, Flyway baseline, Docker Compose, health checks). The social and
playlist shells shipped now have **no backend**, so there is nothing to migrate for them —
only the read slice moves.

### 4. Broadcaster profile (bio + social links) as a separate table

A new `broadcaster_profile` table (V8+V9, schema `stream`) stores the broadcaster's bio and
social links, keyed by `username`:

- **`broadcaster_profile`** (V8): `username VARCHAR(128) PRIMARY KEY`, `bio TEXT`,
  `created_at`, `updated_at`.
- **Social links** (V9): `social_links JSONB` column on `broadcaster_profile`. Stored as a
  JSON array of `{platform, url}` objects. Mapped to `List<SocialLink>` via R2DBC custom
  converters (`@ReadingConverter`/`@WritingConverter`) using `io.r2dbc.postgresql.codec.Json`
  for correct `jsonb` wire-type mapping.
- **`POST /v1/channels/{username}/profile`** — owner-only upsert (JWT `attr.username` must
  match the path `{username}`). Accepts `{bio, socialLinks}`; both fields are independently
  optional.

**Why a separate table and not more columns on `stream_session`?**
- Bio and social links are 1:1 with the broadcaster identity, not 1:N with sessions.
  Putting them on `stream_session` would duplicate data or require "latest session" queries.
- The profile can be updated without any stream existing — a broadcaster can set up their
  bio and links before their first stream.
- `username` as the primary key avoids a UUID→username lookup hop on the channel read path.
  Rename drift is acknowledged as a deferred concern.
- When channel-service is extracted, `broadcaster_profile` lifts out cleanly as the nucleus
  of the channel identity aggregate.

### 5. Channel stats as derived/computed data

`ChannelStats` is computed on-the-fly from the channel's full session list rather than stored
in a materialized table:

| Stat | Computation |
|------|------------|
| `totalStreams` | Count of sessions |
| `totalHoursStreamed` | Sum of `Duration.between(createdAt, updatedAt)` across all sessions, rounded to whole hours |
| `topCategory` | Most frequent category name across all sessions |
| `firstStreamedAt` | `createdAt` of the oldest session (null if no sessions) |
| `categoryBreakdown` | `GROUP BY category` count, sorted descending |

**Why compute and not store?** Zero staleness — stats are always current. No cache
invalidation or refresh job needed. For MVP scale (hundreds of sessions per channel), the
in-memory reduction cost is negligible. If this becomes a bottleneck, materialize into a
`channel_stats` table updated on `STREAM_ENDED` events.

**`totalHoursStreamed` caveat:** Uses `createdAt→updatedAt` duration, which is approximate
for live streams (updatedAt may not reflect actual end time) but correct for ended streams.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Create `channel-service` now | Deferred | Standing up a new service (DB, Flyway baseline, Eureka, gateway route, Docker, health checks) to serve *placeholder* counts is pure overhead — distributed-monolith tax before any domain exists. Extract it when the social/playlist domain is real. |
| Public (guest) channel endpoint with permitAll + gateway allowlist | Deferred | The platform is login-gated for now; a public endpoint adds gateway/security surface and widens exposure with no current need. Revisit if/when guest access is decided (auth/ADR-0002). |
| Runtime username↔UUID lookup (stream-service → auth-service) | Rejected (for now) | Always-fresh, and the internal service-token plumbing exists (`InternalAccessTokenController`), but it adds a hop and a hard read-path dependency on auth-service for every channel view. Denormalization keeps reads self-contained. |
| Gateway/BFF aggregation of auth + stream | Rejected | Heaviest option; introduces an aggregation tier for a read that one service can satisfy after a two-column denormalization. |
| Put channel read permanently in stream-service | Rejected | Couples the social graph and playlists to the stream state machine — two contexts that change for unrelated reasons. Fine as a temporary host, wrong as a permanent home. |

## Consequences

- **Positive:** Ships the visible channel page with **zero runtime cross-service calls** on
  the read path and **no gateway/security changes** (channels stay authenticated). No new
  infrastructure. The social/playlist decision stays open and documented, and the extraction
  cost is bounded (one read slice, two denormalized columns, one profile table).
- **Negative:** stream-service temporarily hosts logic that conceptually belongs to a channel
  domain. Denormalized `broadcaster_username` goes **stale on rename** — old sessions keep the
  old handle until reconciled. Profile data (bio, social links) lives in stream-service's
  schema and would need to migrate with the channel read slice.
- **Risks:**
  - *Cross-user field leakage.* The projection must be verified to exclude
    `broadcaster_subject`/key/RTMP for non-owner callers — tested explicitly (call another
    user's channel, assert absence). This is the endpoint's primary correctness risk.
  - *Rename drift.* Mitigation belongs with the extraction: a `UserRenamed` event
    stream-service (later channel-service) consumes, or a periodic reconcile job. Acceptable
    for shells now.
  - *Backfill gaps.* Existing rows created before the `username` claim existed have no
    resolvable handle; they render with a placeholder until the stream is recreated.
  - *Scope creep into stream-service.* Mitigation: keep the read slice self-contained and
    resist adding social writes here — those wait for channel-service.
  - *Profile write conflicts.* Concurrent updates to bio + social links overwrite each other
    silently (last-write-wins). Acceptable for single-owner profile editing; add `@Version`
    optimistic locking if this becomes a problem.

## References

- Related ADRs: [ADR-0005: Stream Thumbnails](0005-stream-thumbnails.md) (same cheap-field /
  deferred-capability split); [auth/ADR-0002: Authenticated-Visible Username Handle](../auth/0002-public-username-handle-and-login-hardening.md)
  (source of the `username` / `verified_streamer` claims consumed here); [insight/ADR-0000](../insight/0000-architecture-foundation.md)
  (view-tracking trigger on `GET /v1/channels/{username}`)
- Source files: `stream_session` Flyway V7 (denormalized columns), V8 (`broadcaster_profile`),
  V9 (`social_links JSONB`); `StreamSessionEntity.java`, `BroadcasterProfileEntity.java`,
  `StreamController.java`, `StreamService.java`, `StreamSessionRepository.java`,
  `BroadcasterProfileRepository.java`; `ChannelResponse.java`, `ChannelStats.java`,
  `CategoryCount.java`, `SocialLink.java`, `UpdateProfileRequest.java`;
  `SocialLinksReadingConverter.java`, `SocialLinksWritingConverter.java`, `R2dbcConfig.java`
- External docs: [SERVICE-ARCHITECTURE.md](../../SERVICE-ARCHITECTURE.md) — service topology
  and the five-step service lifecycle; [ARCHITECTURE.md](../../ARCHITECTURE.md);
  [channel-page-retrospective.md](../../plans/channel-page-retrospective.md) — full
  implementation retrospective
- Pattern docs: [R2DBC-JSONB-CONVERTER-PATTERN.md](../../R2DBC-JSONB-CONVERTER-PATTERN.md) —
  reusable JSONB column mapping pattern
