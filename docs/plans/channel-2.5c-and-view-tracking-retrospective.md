# Channel Page 2.5c + View Tracking — Implementation Retrospective

**Date:** 2026-07-13
**Status:** Complete (all planned items implemented, uncommitted)
**Reference:** [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) · [ADR-0008](../adr/stream/0008-view-count-analytics-pipeline.md) · [Channel Page Retrospective](channel-page-retrospective.md)

---

## 1. What was implemented (vs the original plan)

### Track A — Channel Page Refactor (2.5c)

| # | Planned Item | Files | Notes |
|---|-------------|-------|-------|
| A1 | Split `GET /v1/channels/{username}` into identity/home/about | `ChannelIdentityResponse.java`, `ChannelHomeResponse.java`, `ChannelAboutResponse.java`, `StreamService.java`, `StreamController.java` | 3 lightweight endpoints, one per tab. Identity: 1-row query. Home: capped at 15 sessions. About: full scan only on-demand. |
| A2 | Database-level broadcast query builder | `BroadcastQueryBuilder.java`, `BroadcastQuery.java` | R2DBC-compatible (no JPA CriteriaBuilder). Server-enforced status filters: rail=ENDED, list=ENDED/LIVE/SCHEDULED. ILIKE keyword search, CASE-based status priority sort, views sort with NULLS LAST. |
| A3 | `GET /broadcasts/recent` + `GET /broadcasts` | `StreamService.java`, `StreamController.java`, `BroadcastPageResponse.java`, `BroadcastPageMeta.java` | Paginated, filterable, SQL-level sorting. Removed in-memory filtering/pagination. |
| A4 | V10 archive migration + archive endpoint | `V10__add_archived_url.sql`, `StreamService.java`, `StreamController.java`, `StreamSessionEntity.java` | Owner-only, ENDED-only. Sets `archived_url` from SRS DVR persistent path. |
| A5 | SRS DVR config | `custom.conf` | `dvr.enabled on`, `dvr_plan session`, persistent volume |
| A6 | `takeUntilDestroyed` fix | `home-tab.component.ts`, `about-tab.component.ts` | Moved HTTP subscriptions from constructor → `ngOnInit`. Constructor fires destroy signal before `ngOnInit` runs. |
| A7 | Tab switching abort fix | `channel.page.ts`, `channel.page.html` | Removed `p-tabpanels` wrapper from `<router-outlet>`. PrimeNG detaches outlet on tab change, destroying components independently of router. Simplified `onTabChange` to single `navigateByUrl`. |
| A8 | "See More" query param fix | `video-tab.component.html`, `rail.component.ts`, `rail.component.html` | `[routerLink]` with plain string URL-encodes `?` → `%3F`. Added `seeMoreQueryParams` input + `[queryParams]` binding. |
| A9 | Child routes for channel tabs | `app.routes.ts`, `channel.page.ts` | `/@username` → redirect `/@username/home`; child routes `/home`, `/video`, `/about` |
| A10 | Channel tab components | `home-tab.component.ts`, `about-tab.component.ts`, `video-tab.component.ts` | Each consumes its own DTO contract from its own endpoint |
| A11 | Frontend contracts | `channel-identity-response.dto.ts`, `channel-home-response.dto.ts`, `channel-about-response.dto.ts` | Replaced monolithic `channel-response.dto.ts` |
| A12 | Deleted files | `ChannelResponse.java`, `channel-response.dto.ts` | Monolithic contract no longer needed |

### Track B — View Counting (ADR-0008 Phase 1 Revision)

| # | Planned Item | Files | Notes |
|---|-------------|-------|-------|
| B1 | Redis connection fix | `application.yml`, `ViewCountFlushService.java` | Added `password`, `timeout`, `connect-timeout` matching chat service. Injected `ReactiveRedisTemplate` instead of building from factory. |
| B2 | V11 `views` column | `V11__add_stream_views.sql`, `StreamSessionEntity.java`, `StreamResponse.java`, `StreamSummaryResponse.java` | `views BIGINT NOT NULL DEFAULT 0` — denormalized unique viewer count |
| B3 | V12 `stream_view_event` table | `V12__create_stream_view_events.sql` | Analytics source-of-truth: `(stream_id, user_id, first_seen_at, last_seen_at)` with UNIQUE constraint |
| B4 | Per-user hash tracking | `StreamService.java#trackViewEvent()` | `stream:view:{streamId}:{viewerId}` Redis Hash with `HSETNX first_seen_at` for dedup. Self-view exclusion. IP fallback for anonymous. |
| B5 | Viewer ID resolution | `StreamController.java#resolveViewerId()` | JWT `sub` → IP fallback via `ServerWebExchange.getRemoteAddress()` |
| B6 | Hash-based flush service | `ViewCountFlushService.java` | SCAN → HGETALL → INSERT ON CONFLICT → recompute `views` from events table → DEL key only after DB success |
| B7 | ViewCountProperties | `ViewCountProperties.java`, `StreamApplication.java` | `@ConfigurationProperties(prefix = "streaming.view-count")` with `viewTtl` (default 24h). Registered via `@EnableConfigurationProperties`. |
| B8 | ADR-0008 revision | `0008-view-count-analytics-pipeline.md` | Updated from simple counter to per-user hash design. Added analytics table, dedup strategy, IP fallback, self-view exclusion, future `watch_duration_seconds` note. |

---

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking Doc | Reason |
|------|------------|-------------|--------|
| Kafka analytics pipeline (Phase 2 of ADR-0008) | insight-service build | [ADR-0008](../adr/stream/0008-view-count-analytics-pipeline.md) §Phase 2 | insight-service not yet built |
| `watch_duration_seconds` column | Client-side heartbeat pings | [ADR-0008](../adr/stream/0008-view-count-analytics-pipeline.md) §Future column note | Requires heartbeat infrastructure; column intentionally omitted (YAGNI) but documented |
| Uploads video rail + list | Phase 8 (VOD + playlist domain) | [IMPLEMENTATION-PLAN.md](../IMPLEMENTATION-PLAN.md) §Phase 4 §Future | Mock data fills the UI; real APIs need video infrastructure |
| Playlist rail + list | Phase 8 | Same as above | Same as above |
| Views column population via actual traffic | Running service with Redis | N/A — operational | Requires `docker compose up` with Redis |

---

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended Action |
|------|---------|-------------------|
| `BroadcastQueryBuilder` pattern | R2DBC has no JPA CriteriaBuilder. The `record BroadcastQuery(sql, bindings)` + `BroadcastQueryBuilder` static factory pattern is a reusable approach for building parameterized SQL queries with `DatabaseClient`. | Write `docs/BROADCAST-QUERY-BUILDER-PATTERN.md` — this pattern applies to any R2DBC service needing dynamic WHERE/ORDER BY/LIMIT clauses without JPA |
| `takeUntilDestroyed` + constructor gotcha | Angular's `takeUntilDestroyed(DestroyRef)` fires the destroy signal when the injector context is destroyed. In the constructor, the component hasn't fully initialized — the destroy signal can fire before `ngOnInit` runs, prematurely aborting HTTP subscriptions. Always use in `ngOnInit`. | Document in the channel page retrospective or as a standalone Angular gotcha note. Already fixed in this session. |
| `p-tabpanel` + `<router-outlet>` incompatibility | PrimeNG `p-tabpanel [value]` wrapping `<router-outlet>` causes competing lifecycle drivers. PrimeNG detaches the outlet on tab change, destroying child components independently of Angular's router. Solution: remove `p-tabpanels`, use plain `<router-outlet>` with `p-tablist` for navigation only. | Document in the channel page retrospective. Already fixed in this session. |

---

## 4. Architectural decisions made during implementation (candidates for new ADRs)

### 4.1 Hash-based per-user view tracking (replaces simple INCR counter)

**Original ADR-0008 design**: `INCR stream:views:{id}` → batch flush → `views += delta`

**Revised design**: `HSET stream:view:{streamId}:{viewerId}` with dedup via key existence

**Why it matters**: Simple INCR has no dedup — F5/refresh/redirect all count as new views. The hash approach naturally deduplicates by key existence within the TTL window, and carries analytics metadata (first_seen_at, last_seen_at) that the insight service can query later.

**Decision**: Revised ADR-0008 before Phase 1 implementation. The marginal complexity of a Hash vs INCR is negligible, and the analytics value is substantial. ADR-0008 already updated.

**Recommendation**: No new ADR needed — the existing ADR-0008 was updated in-place.

### 4.2 IP fallback for anonymous view tracking

**Context**: The platform currently requires authentication, but designing for anonymous viewers from the start avoids a migration later. When no JWT subject is available, the viewer ID falls back to `ip:{remote-address}`.

**Why it matters**: Every endpoint uses `@AuthenticationPrincipal Jwt jwt` today, so the IP fallback is dead code. But it's documented in the ADR and wired in the controller — when anonymous access is added, view tracking works immediately.

**Decision**: Include IP fallback now (trivial cost), Skip anonymous viewer tracking via `doOnSuccess` guard rather than controller-level skip.

**Recommendation**: Captured in ADR-0008 revision. No separate ADR needed.

### 4.3 Self-view exclusion

**Context**: Broadcasters viewing their own stream detail page shouldn't inflate their view counts.

**Decision**: `trackViewEvent()` is called only when `viewerId != entity.getBroadcasterSubject()`. This check happens in the `doOnSuccess` block after the PBAC authorization passes.

**Recommendation**: Captured in ADR-0008 revision. No separate ADR needed.

### 4.4 Three-endpoint channel split (identity / home / about)

**Context**: The monolithic `GET /v1/channels/{username}` fetched everything (identity, session rail, bio, social links, stats) in one response — every tab loaded the same heavy payload. Split into three lightweight endpoints aligned with tab boundaries.

**Decision**: 
- `GET /channels/{username}/identity` — 1-row query for username + verified flag
- `GET /channels/{username}/home` — capped at 15 sessions + derived categories
- `GET /channels/{username}/about` — bio + social links + stats (full scan, on-demand only)

**Recommendation**: Already covered by ADR-0007 (channel read). The 3-way split is a refinement of the existing ADR's "safe cross-user projection" — update ADR-0007 to reference the split if needed, but not required for this retro.

---

## 5. Documents to update (stale vs current state)

| Document | Current Status | What's Stale | Action |
|----------|---------------|-------------|--------|
| `IMPLEMENTATION-PLAN.md` | Last updated 2026-07-12 | 2.5c checklist item unchecked; no mention of ADR-0008 Phase 1 revision or view tracking work | Update checklist, add view tracking items, bump date |
| `docs/adr/stream/0008-view-count-analytics-pipeline.md` | Updated during session | Already current | No action needed |
| `docs/adr/stream/README.md` | Has ADR-0008 entry | May need source file references updated | Verify |
| `docs/plans/channel-page-retrospective.md` | Dated 2026-07-10 | Doesn't cover 2.5c refactor (child routes, endpoint split, broadcast query builder, takeUntilDestroyed fix) | This retro covers it; cross-reference |

---

## 6. Updated execution order (actual vs planned)

| Planned Commit | Actual Status | Notes |
|---------------|---------------|-------|
| 2.5c — Channel page refactor | **Complete, uncommitted** | All backend + frontend changes done; builds pass |
| ADR-0008 Phase 1 — Simple INCR counter | **Revised to hash-based design** | Per-user tracking with dedup + analytics table |
| ADR-0008 Phase 1 — View counting implementation | **Complete, uncommitted** | Redis hash tracking, analytics table, flush service, config |

---

## 7. Key risks carried forward

1. **Redis OOM from view-event keys**: Mitigated by 24h TTL on all `stream:view:*` keys. Key count bounded by (unique streams × unique viewers per 24h).
   **Reference**: [ADR-0008 §Risks](../adr/stream/0008-view-count-analytics-pipeline.md)

2. **Flush crash → stale views column**: If the scheduled flush crashes and stops running, the `views` column goes stale. Mitigation: health-check endpoint monitoring last-flush timestamp.
   **Reference**: [ADR-0008 §Risks](../adr/stream/0008-view-count-analytics-pipeline.md)

3. **COUNT(*) recompute per flush**: Each flush recomputes `views = COUNT(*)` from the events table. For high-traffic streams with many events, this could become expensive. Mitigation: can optimize to incremental `views + 1` for new-unique-only if needed.
   **Reference**: [ADR-0008 §Negative](../adr/stream/0008-view-count-analytics-pipeline.md)

4. **IPv6 in viewer ID**: IPv6 addresses contain `:` which complicates Redis key parsing. Current implementation uses `indexOf(':')` with limit — only the first colon after the UUID separates streamId from viewerId, so IPv6 addresses in the viewerId portion are preserved intact. Needs verification with real IPv6 traffic.
   **Reference**: [ADR-0008 §Negative](../adr/stream/0008-view-count-analytics-pipeline.md)

---

## 8. Patterns discovered (candidates for pattern docs)

### 8.1 R2DBC Broadcast Query Builder

**Pattern**: `record BroadcastQuery(String sql, Map<String, Object> bindings)` + `BroadcastQueryBuilder` static factory methods.

**When to use**: Any R2DBC service that needs dynamic WHERE clauses, ORDER BY, LIMIT/OFFSET without JPA CriteriaBuilder. The query object carries both the parameterized SQL and its bindings, and `DatabaseClient.sql(query.sql()).bindValues(query.bindings())` executes it.

**Where it's used**: `StreamService.getRecentBroadcasts()`, `StreamService.getBroadcasts()`

**Recommendation**: Write `docs/BROADCAST-QUERY-BUILDER-PATTERN.md` if this pattern is reused in another service.

### 8.2 `takeUntilDestroyed` must be in `ngOnInit`

**Pattern**: `takeUntilDestroyed(this.destroyRef)` in Angular components must be used in `ngOnInit`, never in the constructor. The `DestroyRef` is tied to the injector context — in the constructor, the component hasn't fully initialized and the destroy signal can fire prematurely, aborting subscriptions before they complete.

**Where it bit us**: `HomeTabComponent`, `AboutTabComponent` — HTTP subscriptions in constructor with `takeUntilDestroyed` were aborted before rendering.

**Recommendation**: Already fixed. Document as an Angular gotcha if it recurs.

### 8.3 `p-tabpanel` + `<router-outlet>` incompatibility

**Pattern**: Never wrap `<router-outlet>` in PrimeNG `p-tabpanel [value]`. PrimeNG manages panel visibility by detaching/reattaching DOM, which destroys and recreates routed components independently of Angular's router. Use plain `<router-outlet>` with `p-tablist` as navigation-only controls.

**Where it bit us**: `ChannelPage` — tab switching caused components to be destroyed and recreated, aborting HTTP requests.

**Recommendation**: Already fixed. Document as a PrimeNG + Angular routing gotcha.
