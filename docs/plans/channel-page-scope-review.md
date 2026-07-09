# Channel Page — Pre-Implementation Scope Review

**Date:** 2026-07-09
**Status:** Awaiting your review before implementation
**Supersedes framing in:** `channel-page-phase-a-b-blueprint.md` (this doc adjusts it for the "authenticated-only" decision)

This is a temporary decision doc. Once you approve, I'll apply the ADR edits and start Phase A.

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

### Already written (keep)
- **auth/0002** — username→JWT claim + login hardening. Core decision still valid.
- **stream/0007** — denormalize identity + channel-service extraction seam. Core decision still valid.

### Needs updating (I'll edit after you approve)
- **auth/0002** — reframe from "public username handle" to **"authenticated-visible username handle"**:
  - Context: username becomes visible to *authenticated users*, not the open internet.
  - Login hardening: keep, but reframe as **general login hygiene + enumeration-oracle fix**, not a gate for public exposure. Note guest/public access as an explicit **deferred decision** (its own future ADR).
  - Decision on errors: lock in **single 401** (drop the "different messages/status" language).
- **stream/0007** — change "public read" → **"authenticated read"**:
  - Remove the gateway-allowlist consequence and the `permitAll` step.
  - Keep and emphasize the safe-projection requirement (now protecting cross-user leakage, not guest leakage).
  - Note guest access deferred.

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
- Angular `ChannelPage` + shared organisms; session rail (real data), category strip (real).

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

## Proposed execution order (after approval), committed feature-by-feature

Each numbered item is its own focused commit (per the feature-by-feature commit preference):

1. **ADR edits** — auth/0002 reframe, stream/0007 reframe, new auth/0003 + README indexes. *(done)*
2. **feat(auth): username in JWT attr claim** — V9 seed + resolver + `SubjectAttributes`.
3. **fix(auth): uniform 401 on login** — collapse 404-vs-401 enumeration oracle.
4. **feat(auth): Redis-Lua login rate limiter** — Lua script + service + 429 exception + wiring.
5. **feat(stream): denormalize broadcaster identity** — V7 columns + populate from `attr` at create.
6. **feat(stream): authenticated channel read endpoint** — `ChannelResponse` safe projection + repo finder + controller.
7. **feat(ui): username signal + channel contracts** — `TokenAttrDto.username`, `myUsername`, `channel-response.dto`, `getChannel`.
8. **feat(ui): channel page route + container + organisms** — `/@:username`, shared organisms, owner-mode gating.
9. **feat(ui): channel header entry point** — app-shell "Channel" → own handle; retire/redirect `dashboard/streams`.
