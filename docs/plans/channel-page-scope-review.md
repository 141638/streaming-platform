# Channel Page — Scope & Vision (authoritative planning doc)

**Date:** 2026-07-09
**Updated:** 2026-07-10 (post-implementation review)
**Status:** Phase A+B shipped. About tab expanded beyond original scope (see §0.1).
**Companion:** [`channel-page-phase-a-b-blueprint.md`](channel-page-phase-a-b-blueprint.md) holds the file-level how-to; [`channel-page-retrospective.md`](channel-page-retrospective.md) holds the full post-implementation analysis. On any conflict, **this doc wins** (the blueprint body was reconciled to match it).

This is the entry point for anyone (incl. a cold session) picking up the channel work.

---

## 0. North Star — the full vision (what a channel ultimately becomes)

The end goal is a **Twitch-style channel** at `/@username`: a creator's home on the platform,
**stream-focused** (not a general video site — no shorts/reels).

The complete picture, once every phase is done:

- **A live/last-session stage as the backdrop.** The current stream (or the latest when
  offline) plays full-height behind the page; header and body float over it.
- **An identity header** — avatar, channel name, **verified** check, follower count (K/M/B
  formatted), subscriber & video counts, and the username handle. Action cluster on the
  right: **Follow** (free), **Subscribe** (paid membership), **Gift Sub** (buy a sub for
  another viewer/named user).
- **A body of tabs** — **Home** first:
  - a horizontal, drag/chevron-scrollable rail of the **latest 10–15 stream sessions**;
  - **manager-curated playlists** — the channel owner picks which of their playlists (a
    playlist = a wrapper around any videos) render here, each as its own horizontal rail;
  - a strip of the channel's **recently-streamed categories**.
- **One page, two audiences** — a *viewer* sees Follow/Subscribe/Gift; the *owner* (channel
  manager) sees manage affordances (edit channel, manage playlists, and a link into the
  existing publish-key/lifecycle control panel). Same page, ownership-gated — not two pages.
- **Eventually, possibly public** — today the whole platform is login-gated; opening channels
  to guests (for discovery/SEO) is a deliberately deferred, revisitable decision.

**How we get there without over-building:** this round ships the *shell* of that vision —
real where it's cheap (identity, session rail, categories), disabled/empty placeholders
where it's expensive (followers, subscribe, gift, playlists). Those expensive domains are
built later in a dedicated **channel-service**. The section below is the exact line between
*now*, *shell now*, and *later*.

---

## 0.1 Full feature → scope map (every piece of the vision)

| Vision piece | This round | How | Later phase |
|---|---|---|---|
| `/@username` route + channel page | ✅ Build | Angular `ChannelPage`, authenticated route | — |
| Full-height stage backdrop | ✅ Build | Reuse existing `StreamStageComponent` | — |
| Avatar + channel name | ✅ Build | From `ChannelResponse` | — |
| **Verified** check | ✅ Build (real) | `verified_streamer` already in JWT → denormalized | — |
| Username handle display | ✅ Build (real) | New `username` JWT claim → denormalized | — |
| Latest 10–15 sessions rail | ✅ Build (real) | Existing session data + new `getChannel` | — |
| Recently-streamed categories strip | ✅ Build (real) | Existing categories, distinct-by-broadcaster | — |
| Follower **count** | 🟡 Shell | Static/placeholder number, visibly disabled | **channel-service** (social graph) |
| Subscriber / video counts | 🟡 Shell | Static/placeholder | channel-service |
| **Follow** button | 🟡 Shell | Disabled button in header action slot | channel-service (followers) |
| **Subscribe** (paid membership) | 🟡 Shell | Disabled button | channel-service + **payments** |
| **Gift Sub** | 🟡 Shell | Disabled button | channel-service + payments |
| **Playlists** (manager-curated rails) | 🟡 Shell | Empty "No playlists yet" rail — deferred until VOD/video upload is available | channel-service (playlist domain + manager CRUD) |
| **Videos** tab | 🟡 Shell | Disabled tab — requires VOD upload + video management backend | Phase 4+ (VOD infrastructure) |
| **About** tab | ✅ Build (real) | Bio display + owner inline edit (V8 `broadcaster_profile`); social links display + owner add/remove edit (V9 `social_links JSONB` + R2DBC converters); derived channel stats sidebar with total streams, hours streamed, top category, streaming-since date, and per-category breakdown; `POST /v1/channels/{username}/profile` | Schedule, achievements (unspecced) |
| Owner manage affordances | ✅ Build (minimal) | `@if(isOwner)` → link into existing `/channel/:id` panel | richer manage UI later |
| Guest / public (no-login) access | ❌ Not now | — | **Own future ADR** (revisitable) |
| Rename-drift reconciliation | ❌ Not now | — | channel-service (`UserRenamed` event / job) |
| Login rate-limiter + uniform-401 | ✅ Build | auth-hardening, its own commits (auth/0003) | — |

Legend: ✅ real & working this round · 🟡 visible shell, no backend this round · ❌ deferred.

**One-line summary of the scope boundary:** *this round = identity + read + the visual shell
of the whole channel; everything that needs a social graph, payments, or a playlist store is
a disabled/empty placeholder now and a `channel-service` job later.*

---

## Decisions taken this round

1. **Authenticated-only** — the whole service stays behind login. No guest/public access to channels. (Accepted trade: less organic/SEO discovery; fine for a pet project. Explicitly revisitable in a later phase.)
2. **Single 401 on login** — throw one "invalid credentials" (401) for both unknown-user and bad-password. Kill the 404-vs-401 enumeration oracle.
3. Migration numbers, `attr`-map reading, denormalization approach — confirmed as blueprinted.

---

## 1. Scope changes — what's updated vs removed

### Updated (vs the original blueprint)
| Item | Original (public) | Now (authenticated-only) |
|---|---|---|
| `/v1/channels/{username}` access | `permitAll`, guest-reachable | Left under `anyExchange().authenticated()` — **any logged-in user** can view any channel |
| stream-service `SecurityConfig` | add `permitAll` GET matcher | **no change needed** — default-authenticated already covers it |
| Gateway | allowlist `/api/streams/v1/channels/**` | **not needed** — gateway keeps requiring a token |
| `/@:username` route | public route outside `authGuard` | **inside the `authGuard` shell** (like the rest of the app) |
| Safe-projection DTO | matters for guests | **matters MORE** — user A must never see user B's `broadcasterSubject` / publish key / RTMP. This stays a hard requirement. |
| Login hardening (A2) | prerequisite gate for going public | **decoupled** — no longer blocks the channel work (see §5) |

### Removed from scope this round
- Gateway route/security change for channels.
- `permitAll` matcher for channels in stream-service.
- Public/guest rendering path, SEO/meta considerations.
- (Deferred, unchanged) real followers/subscriptions/gifting/playlists, email-or-username login, rename reconciliation.

### Net backend surface still in scope
- auth-service: `username` in JWT `attr` (V9 seed + resolver + DTO); **uniform 401** on login.
- stream-service: V7 denormalized columns; populate from `attr` at create; **authenticated** `GET /v1/channels/{username}` returning a **safe projection**; repo finder by username.

---

## 2. ADR impact

### Written & committed (done — `487afa1`)
- **auth/0002** — *Authenticated-Visible Username Handle*: username→JWT claim + uniform-401 enumeration fix; guest access noted as deferred.
- **auth/0003** — *Redis-Lua Login Brute-Force Rate Limiting*.
- **stream/0007** — *Authenticated Channel Read*: denormalize identity + safe cross-user projection + channel-service extraction seam.
- Both ADR README indexes updated.

### Future planned ADRs (write when the phase starts — like the reserved MinIO ADR-0006)
- **channel-service extraction** — when social/playlist becomes real (referenced already in stream/0007).
- **social graph** (followers → subscriptions → gifting; gifting pulls in payments — likely its own ADR).
- **playlists domain.**
- **guest / public channel access** — if/when we decide to open the gate (auth/0002 flags this as revisitable).

---

## 3. Scaffold now vs separated later

### Built now (real, in existing services)
- JWT `username` claim (auth-service).
- Uniform 401 login error (auth-service).
- Denormalized `broadcaster_username` / `broadcaster_verified` on `stream_session`.
- Authenticated `GET /v1/channels/{username}` + safe `ChannelResponse` projection.
- Channel bio via `broadcaster_profile` table (V8) + social links via `social_links JSONB` column (V9) with R2DBC custom converters.
- `POST /v1/channels/{username}/profile` (owner-only upsert for bio + social links).
- Derived `ChannelStats` (totalStreams, totalHoursStreamed, topCategory, firstStreamedAt, categoryBreakdown) computed on-the-fly from session data.
- Angular `ChannelPage` + shared organisms; session rail (real data), category strip (real), About tab (bio display + owner edit, social links display + owner add/remove, stats sidebar with category breakdown).

### Scaffolded shells now (no backend, visibly disabled)
- Follower / subscriber / video counts.
- Follow / Subscribe / Gift buttons.
- Playlists rail ("No playlists yet").

### Separated into a future `channel-service`
- Followers, subscriptions, gifting, playlists, rename-drift reconciliation.
- The current channel read slice (endpoint + denormalized columns) is written self-contained so it **lifts out cleanly** when that service is created. The shells have no backend, so nothing to migrate for them.

---

## 4. Viewer vs Channel-Manager (streamer) — separation with organism reuse

The two views are ~80% identical (same stage, rails, tabs, identity chrome); only the **action affordances** differ. So: **shared dumb organisms + one smart container that switches on ownership.** Not two full pages.

```
pages/channel/channel.page.ts            # smart container
  isOwner = computed(myUsername() === username())
  fetches ChannelResponse, drives organisms, gates owner-only extras
        │
        ├─ organisms/channel-header/     # SHARED — input-driven
        │     identity (name, verified, avatar, counts)
        │     action slot swaps by mode:
        │        viewer  → Follow / Subscribe / Gift   (disabled shells)
        │        owner   → Edit channel / Manage        (owner-only)
        │
        ├─ organisms/stream-stage/        # SHARED (reused, full-height background)
        ├─ organisms/session-rail/        # SHARED (drag + chevron scroll)
        ├─ organisms/playlist-rail/       # SHARED (empty shell for now)
        └─ molecules/category-strip/      # SHARED
                                          # owner-only, behind @if(isOwner()):
        └─ organisms/channel-manage-bar/  # link to existing publish-key/lifecycle
                                          # control panel (/channel/:id), "add playlist"
```

**Reuse strategy for the header** (the only organism that differs by mode): keep it dumb and feed the right-hand action cluster via **content projection (`ng-content` slot)** or an `isOwner` input that toggles between two small action molecules. Content projection keeps the header itself free of viewer/owner logic — the container decides what goes in the slot. Either works; I lean content-projection for cleanliness.

**Owner controls are not rebuilt** — the existing `/channel/:id` publish-key + lifecycle panel stays the source of truth; the channel page (owner mode) just links into it via the manage bar. No duplication of that logic.

**Result:** one container, one set of organisms, ownership gates the extras. Adding real Follow/Subscribe later means swapping the disabled shells for live molecules in the same header slot — no structural change.

---

## 5. Login rate-limiting — DECIDED: keep this round, own ADR

Locked: build the **uniform-401** *and* the **Redis-Lua rate-limiter** this round, as a
standalone auth-hardening step independent of the channel feature. Captured in
**auth/ADR-0003** (Redis-Lua Login Brute-Force Rate Limiting).

---

## Execution order (actual, post-implementation)

Each numbered item was its own focused commit (per the feature-by-feature commit preference).
Items marked ❌ were deferred.

1. ✅ **ADR edits** — `487afa1`, `339c449`, `da556f0`: auth/0002, stream/0007, auth/0003 + README indexes + insight/0000.
2. ✅ **feat(auth): username in JWT attr claim** — `1a0617f`: V9 seed + resolver + `SubjectAttributes`.
3. ❌ **fix(auth): uniform 401 on login** — deferred. ADR'd (auth/0002) but not implemented.
4. ❌ **feat(auth): Redis-Lua login rate limiter** — deferred. ADR'd (auth/0003) but not implemented.
5. ✅ **feat(stream): denormalize broadcaster identity** — `e12d3d1`: V7 columns + `JwtAttr` helper.
6. ✅ **feat(stream): authenticated channel read endpoint** — `158b5c9`, `50e9cb1`: `ChannelResponse` + stats + categories.
7. ✅ **feat(ui): username signal + channel contracts** — `e0120ee`: contracts + auth service + `getChannel()`.
8. ✅ **feat(ui): channel page route + container + organisms** — `e0120ee`, `1d5de98`, `4dd62ca`: `/@:username` + all components + About tab + UI polish.
9. ✅ **feat(ui): channel header entry point** — `e0120ee`: app-shell "Channel" → `/@<myUsername>`.

**Beyond planned scope (shipped):**
- ✅ **feat(stream): broadcaster profile + social links + channel stats** — `158b5c9`: V8+V9 migrations, profile endpoint, `ChannelStats`, JSONB converters.
- ✅ **fix(stream): JSONB converter wire-type** — `af1f62c`, `991c67d`: `Json.of()` for write, `Json` input for read.
- ✅ **feat(ui): two-column About tab with stats sidebar + category breakdown** — `1d5de98`, `4dd62ca`.
