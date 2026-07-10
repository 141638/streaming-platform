# Channel Page — Implementation Retrospective

**Date:** 2026-07-10
**Status:** Complete (Phase A+B shipped; see §4 for deferred items)
**Reference:** [Blueprint](channel-page-phase-a-b-blueprint.md) · [Scope Review](channel-page-scope-review.md) · [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md)

---

## 1. What was implemented (vs the original plan)

### Phase A — Backend Identity + Authenticated Read

| # | Planned Item | Commit(s) | Notes |
|---|-------------|-----------|-------|
| A1 | `username` in JWT `attr` claim | `1a0617f` | auth-service V9: seed row + resolver case + `SubjectAttributes` field |
| A3 | Denormalize broadcaster identity on `stream_session` | `e12d3d1` | V7 migration: `broadcaster_username VARCHAR(128)` + `broadcaster_verified BOOLEAN`. Populated from JWT `attr` at stream create. |
| A4 | `GET /v1/channels/{username}` safe cross-user projection | `158b5c9`, `50e9cb1` | `ChannelResponse` excludes `broadcasterSubject`, publish key, `rtmpUrl`. `ChannelStats` computed from session data. |

### Phase B — Frontend Channel Shell

| # | Planned Item | Commit(s) | Notes |
|---|-------------|-----------|-------|
| B1 | Contracts + auth service | `e0120ee` | `TokenAttrDto.username`, `myUsername` signal, `ChannelResponseDto`, `StreamService.getChannel()` |
| B2 | `/@:username` route | `e0120ee` | `channelMatcher` function; inside `authGuard` (authenticated-only) |
| B3 | Channel page organisms | `e0120ee`, `1d5de98`, `4dd62ca` | See component inventory below |
| B4 | Header entry point | `e0120ee` | "Channel" menu → `/@<myUsername>` |

### Component Inventory (all shipped)

| Component | Type | Path | Key decisions |
|-----------|------|------|---------------|
| `ChannelPage` | Page | `pages/channel/` | Smart container, `OnPush`, route input `username`, owner gating via `isOwner()` computed |
| `ChannelHeaderComponent` | Organism | `shared/organisms/channel-header/` | DiceBear avatar, verified badge, content-projected action slots (viewer/owner) |
| `SessionRailComponent` | Organism | `shared/organisms/session-rail/` | 20rem cards, 8rem thumbnails, drag-to-scroll + chevrons, floating date labels, category portrait thumbnails (3.2/4) |
| `CategoryStripComponent` | Molecule | `shared/molecules/category-strip/` | Portrait cards (12rem), gradient backgrounds from name hash, pseudo-subscriber counts |
| `PlaylistRailComponent` | Organism | `shared/organisms/playlist-rail/` | Empty "No playlists yet" shell |
| `SocialLinksComponent` | Molecule | `shared/molecules/social-links/` | PrimeIcons per platform, tooltip-labeled `<a>` buttons, null-safe |
| `CategoryCountDto` | Contract | `core/contracts/channel-stats.dto.ts` | `category`, `count` fields |
| `formatCount` util | Lib | `lib/format-count.ts` | K/M/B number formatting |

### Beyond the Blueprint — About Tab (implemented during the session)

These items were not in the original Phase A+B blueprint but were designed and shipped:

| Item | Backend | Frontend |
|------|---------|----------|
| **Bio display + edit** | V8: `broadcaster_profile` table (`username` PK, `bio TEXT`, timestamps), `BroadcasterProfileEntity`, `BroadcasterProfileRepository` | Owner: inline textarea edit with save/cancel, char counter (2000 max), `white-space: pre-wrap` rendering |
| **Social links display + edit** | V9: `social_links JSONB` column, `SocialLinksReadingConverter` + `SocialLinksWritingConverter`, `UpdateProfileRequest` DTO | Owner: per-platform add/remove form, `SocialLinksComponent` for display; viewer sees links as icon buttons |
| **Channel stats** | `ChannelStats` record: `totalStreams`, `totalHoursStreamed`, `topCategory`, `firstStreamedAt` (oldest session), `categoryBreakdown` (List\<CategoryCount\>) | Two-column layout: bio+links (left, flex-1) \| stats sidebar (right, 16rem) with key metrics + category breakdown list |
| **Profile endpoint** | `POST /v1/channels/{username}/profile` — owner-only (JWT username check), upserts bio + socialLinks | `updateProfile()` in `StreamService`, save with optimistic UI update |
| **JSONB converter pattern** | `SocialLinksReadingConverter` (Json→List\<SocialLink\>) + `SocialLinksWritingConverter` (List\<SocialLink\>→Json), registered in `R2dbcConfig` | N/A — backend-only pattern |

---

## 2. What was deferred (documented, with tracking reference)

These items were in the original scope review or discussed during implementation, explicitly deferred, and have a tracking note:

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| **Login rate limiter** (A2) | Phase 6.3 or standalone hardening pass | [auth/ADR-0003](../adr/auth/0003-login-rate-limiting.md) (Proposed) | Decoupled from channel work; ADR written, implementation deferred |
| **Uniform 401 on login** | Same as above | [auth/ADR-0002](../adr/auth/0002-public-username-handle-and-login-hardening.md) | Collapse 404-vs-401 enumeration oracle — ADR'd, not implemented |
| **Real followers / subscriptions / gifting** | channel-service extraction | [Scope review §3](channel-page-scope-review.md#3-scaffold-now-vs-separated-later) | Needs social graph domain + payments |
| **Playlist domain** | channel-service extraction | Scope review §0.1 | Requires VOD/video upload infrastructure first |
| **Videos tab** | Phase 4+ (VOD infrastructure) | Scope review §0.1 | Tab is disabled in UI; needs video upload + management backend |
| **Guest/public channel access** | Own future ADR | [auth/ADR-0002](../adr/auth/0002-public-username-handle-and-login-hardening.md) | Explicitly revisitable; platform is login-gated for now |
| **Rename-drift reconciliation** | channel-service extraction | [stream/ADR-0007](../adr/stream/0007-public-channel-read-and-channel-service-seam.md) | `UserRenamed` event or periodic reconcile job |
| **Email-or-username login, MFA, HIBP** | Future auth-hardening | [auth/ADR-0002](../adr/auth/0002-public-username-handle-and-login-hardening.md) §deferred list | — |
| **SRS snapshot thumbnails** (2.9) | Phase 4.0 (SRS Docker) | [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md#29--srs-snapshot-thumbnails) | Depends on SRS Docker service existing |
| **Kafka integration testing** (2.6) | Later | [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md#26--kafka-integration-testing) | Zero Kafka tests exist |
| **Channel stats: hours-streamed precision** | Later | N/A — noted in `StreamService.computeStats()` | Currently uses `Duration.between(createdAt, updatedAt)` which may overestimate for live streams; acceptable for MVP |

---

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

These items came up during implementation, were verbally deferred, but have no tracking note, ADR, or deferred-feature catalog entry:

| Item | Context | Recommended action |
|------|---------|-------------------|
| **"Edit Channel" button is disabled** | In `ChannelHeaderComponent` owner slot — labeled but non-functional | Already tracked in scope review §0.1 (owner manage affordances are "minimal" this round); no new doc needed |
| **Follower/subscriber/video counts are hardcoded placeholders** | Shows "0" / "0" / "0" in the header | Already tracked in scope review §0.1; no new doc needed |
| **Session rail "More actions" (⋮) button is disabled** | Tooltip says "More actions coming soon" | Not tracked anywhere. **Action:** add to scope review deferred list or create a "UI shell backlog" note |
| **Category strip "Follow" button is disabled** | Every category card has a disabled Follow button | Not tracked. **Action:** add to scope review (part of social graph dependency) |
| **Session rail dots menu has no functionality** | Placeholder only | Not tracked. **Action:** note as deferred until session actions (clip, report, share) are designed |
| **No error recovery UI for failed profile updates** | `saveBio()` and `saveLinks()` keep the edit form open on error but show no feedback message | Not tracked. **Action:** add to UI polish backlog; needs a toast/notification system |
| **`firstStreamedAt` may be misleading for backfilled data** | Uses oldest session `createdAt`; pre-V7 sessions with NULL username are still included in stats | Edge case, not urgent. Document in `ChannelStats` Javadoc |

---

## 4. Architectural decisions made during implementation (candidates for new ADRs)

These decisions shaped the implementation but are not captured in any existing ADR:

### 4.1 JSONB Column Mapping via R2DBC Converters

**Decision:** Map PostgreSQL `jsonb` columns to Java domain types (`List<SocialLink>`) using Spring Data R2DBC custom converters that return `io.r2dbc.postgresql.codec.Json` (not `String`).

**Why it matters:** The R2DBC Postgres driver inspects the Java type to determine the PG wire type. Returning `String` from a writing converter sends `character varying` — PostgreSQL rejects the write with `"column is of type jsonb but expression is of type character varying"`. Returning `Json` tells the driver to use the correct `jsonb` wire type. Similarly, reading converters must accept `Json` (not `String`) because the driver provides `Json` for `jsonb` columns.

**Converter contract:**
- `@ReadingConverter`: `Converter<Json, List<SocialLink>>` — parse `source.asString()` with Jackson, return empty list on failure
- `@WritingConverter`: `Converter<List<SocialLink>, Json>` — serialize to JSON string, wrap with `Json.of(jsonString)`
- Both registered in `R2dbcConfig` via `getCustomConverters()`

**Reuse potential:** This pattern applies to all future JSONB columns (insight-service engagement metadata, channel-service playlist config, etc.). The converter pair is domain-type-specific (e.g., `SocialLinksReadingConverter`) but the `Json.of()` / `Json.asString()` contract is universal.

**Recommendation:** Write as a **pattern reference doc** (`docs/R2DBC-JSONB-CONVERTER-PATTERN.md`) rather than a full ADR — the decision itself is straightforward (use the driver's `Json` type), but the pattern is easy to get wrong and worth documenting.

### 4.2 BroadcasterProfile as a Separate Table (not on stream_session)

**Decision:** Store bio and social links in a new `broadcaster_profile` table (V8+V9) keyed by `username VARCHAR(128)`, rather than adding columns to `stream_session`.

**Why it matters:**
- **1:1 with the username, not 1:N with sessions.** Bio and social links belong to the broadcaster identity, not to individual streams. Putting them on `stream_session` would duplicate data or require complex "latest session" queries.
- **Independent lifecycle.** Bio/social links can be updated without a stream existing. `stream_session` rows require a stream.
- **Clean extraction seam.** When channel-service is extracted, `broadcaster_profile` lifts out as the nucleus of the channel identity aggregate — it has no dependency on stream lifecycle.
- **Username as PK is deliberate.** The channel read path is `/v1/channels/{username}` — using username as the PK avoids a UUID lookup hop. Rename drift is acknowledged as a deferred concern (see ADR-0007).

**Recommendation:** This decision is partially covered by ADR-0007 (which mentions "denormalized identity") but ADR-0007 focuses on the `stream_session` columns. The `broadcaster_profile` table design (separate table, username PK, bio + JSONB social links) is worth an ADR or at least a section in the updated ADR-0007.

### 4.3 ChannelStats as Derived/Computed Data (not stored)

**Decision:** `ChannelStats` (totalStreams, totalHoursStreamed, topCategory, firstStreamedAt, categoryBreakdown) is computed on-the-fly from the channel's full session list, not stored in a materialized table.

**Why it matters:**
- **Zero staleness.** Stats are always current — no cache invalidation or refresh job needed.
- **Acceptable cost for MVP scale.** A channel with 100 sessions does ~100 in-memory reductions. If this becomes a bottleneck (thousands of sessions per channel), materialize into a `channel_stats` table updated on stream-end events.
- **`totalHoursStreamed` uses `Duration.between(createdAt, updatedAt)`.** This is approximate for live streams (updatedAt may not reflect actual end time) but correct for ended streams.

**Recommendation:** No separate ADR needed — the decision is simple and documented in `ChannelStats.java` and `StreamService.computeStats()`. Add a note to the implementation plan.

### 4.4 Client-Side Owner-vs-Viewer Gating

**Decision:** The server returns the same `ChannelResponse` for both owner and viewer. Edit affordances (bio textarea, social links form) are gated client-side by comparing `authService.myUsername() === username()`. The server enforces ownership at the write endpoint (`POST /v1/channels/{username}/profile` checks JWT username).

**Why it matters:** This follows the existing platform pattern (stream detail page shows lifecycle controls based on ownership). The alternative — returning different responses for owner vs viewer — would complicate caching and the API contract without adding security (the server already enforces ownership on writes).

**Recommendation:** Covered by ADR-0007 §2 ("Owner-vs-visitor affordances are decided client-side"). No new ADR needed.

### 4.5 Two-Column About Tab Layout

**Decision:** About tab uses a two-column layout: bio + links (left, `flex-1`) | stats sidebar (right, 16rem, `flex-shrink: 0`). On mobile (< 1024px), columns stack vertically.

**Why it matters:** This is a UX decision, not architectural. The right column was chosen to fill the empty space in the About tab. The stats sidebar with category breakdown makes the tab feel complete without needing deferred features (schedule, achievements, etc.).

**Recommendation:** No ADR needed — UX decision documented here for completeness.

---

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) | Last updated 2026-07-08 | No mention of channel page completion, About tab, JSONB pattern, or insight ADR-0000 | Add channel page as a completed work item; update Phase 4 readiness |
| [ADR stream/0007](../adr/stream/0007-public-channel-read-and-channel-service-seam.md) | **Proposed** | Implementation is complete; About tab (bio, social links, stats, profile endpoint) not mentioned | Accept the ADR; add § on BroadcasterProfile + ChannelStats + profile endpoint |
| [channel-page-scope-review.md](channel-page-scope-review.md) | Approved, pre-implementation | About tab row says "Bio display + owner edit" only; social links are now real, stats are now real, category breakdown exists | Update About tab row to reflect actual implementation; add completed checkmarks |
| [channel-page-phase-a-b-blueprint.md](channel-page-phase-a-b-blueprint.md) | "Ready for review" | No mention of About tab; all tasks are in future tense | Mark completed; add post-implementation notes for About tab items not in original blueprint |
| [auth/ADR-0003](../adr/auth/0003-login-rate-limiting.md) | Proposed | Implementation not done — needs a status note or deferral decision | Leave as Proposed; the retrospective now tracks it |
| R2DBC JSONB pattern | Doesn't exist | Pattern was discovered through trial/error; reusable for future services | Write `docs/R2DBC-JSONB-CONVERTER-PATTERN.md` |

---

## 6. Updated execution order (actual vs planned)

Planned (9 commits) vs actual (10+ commits):

| # | Planned | Actual commit | Status |
|---|---------|---------------|--------|
| 1 | ADR edits | `487afa1`, `339c449`, `da556f0` | ✅ Done (3 commits) |
| 2 | `feat(auth): username in JWT attr claim` | `1a0617f` | ✅ Done |
| 3 | `fix(auth): uniform 401 on login` | — | ❌ Deferred (ADR'd, not implemented) |
| 4 | `feat(auth): Redis-Lua login rate limiter` | — | ❌ Deferred (ADR'd, not implemented) |
| 5 | `feat(stream): denormalize broadcaster identity` | `e12d3d1` | ✅ Done |
| 6 | `feat(stream): authenticated channel read endpoint` | `158b5c9`, `50e9cb1` | ✅ Done (expanded — 2 commits) |
| 7 | `feat(ui): username signal + channel contracts` | `e0120ee` (bundled) | ✅ Done |
| 8 | `feat(ui): channel page route + container + organisms` | `e0120ee` | ✅ Done |
| 9 | `feat(ui): channel header entry point` | `e0120ee` (bundled) | ✅ Done |
| — | **Beyond plan: About tab (bio + social links + stats)** | `158b5c9`, `e0120ee`, `1d5de98` | ✅ Done (3 commits) |
| — | **Beyond plan: JSONB converter pattern** | `af1f62c`, `991c67d` | ✅ Done (2 commits — write + read fix) |
| — | **Beyond plan: UI polish (session rail, category thumbs, alignment)** | `4dd62ca` | ✅ Done |
| — | **Beyond plan: insight ADR-0000** | `339c449` | ✅ Done |

---

## 7. Key risks carried forward

1. **Rename drift** — `broadcaster_username` on old sessions goes stale on rename. Mitigation: reconciliation job when channel-service is extracted ([ADR-0007](../adr/stream/0007-public-channel-read-and-channel-service-seam.md)).
2. **Backfill gaps** — pre-V7 sessions have NULL `broadcaster_username`. Rendered with placeholder. No backfill source without a username↔UUID lookup.
3. **No channel read tests** — `GET /v1/channels/{username}` has integration tests in `StreamServiceTest` but no dedicated controller test verifying the safe projection (no `broadcasterSubject`/key/RTMP leakage).
4. **`firstStreamedAt` edge case** — uses oldest session `createdAt` which may include NULL-username backfill rows.
5. **Profile update has no conflict detection** — concurrent updates to bio + social links overwrite each other silently (last-write-wins). Acceptable for single-owner profile editing.
