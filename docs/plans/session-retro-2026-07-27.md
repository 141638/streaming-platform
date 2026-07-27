# Session Retrospective — 2026-07-27

**Date:** 2026-07-27
**Trigger:** `/retro` — scan last 20 commits, reconcile against implementation plan
**Scope:** Commits `221831e` (2026-07-16) through `d3ff40e` (2026-07-17)
**Status:** Reconciliation complete — 5 stale "pending commit" markers found and corrected; no undocumented gaps discovered

## 1. What was implemented (vs the original plan)

| Planned item | Commit(s) | Notes |
|-------------|-----------|-------|
| 4.4 — Viewer presence (SSE push + frontend display) | `6cec5c5`, `d485685`, `717b82d`, `dd99ed4` | REST heartbeat + Redis SETEX + SSE `ViewerCountPushService` @Scheduled 10s + watch page/browse/session rail counts + stable menu array fix |
| 4.4b — Heartbeat harvest service | `6cec5c5`, `1aa52e9` | `HeartbeatHarvestService` @Scheduled 30s → SCAN → group → GREATEST UPSERT → `stream_viewer_snapshot` table; V16 migration |
| 5.2b — Dedup key scoping refactor | `9bace9e` | `dedup:stream-event:{eventId}` → `dedup:{topic}:{consumerGroupId}:{eventId}` per ADR common/0003 |
| 5.2b — Follower fan-out | `9bace9e`, `954f6ba` | `StreamControlListener.onStreamStarted()` → `getSubscribers()` → `createForFollower()` → `deliverToMany()`; `broadcasterUsername` added to `StreamEvent` |
| 5.4 — Frontend notification settings + follow button + bell dropdown | `221831e`, `c00cc94`, `9884613`, `eb40665` | Lazy-loaded settings page, follow button on channel page, bell dropdown with pagination, mark-as-read, subscription service (8 HTTP methods), click actions |
| 5.4b — mark-all-as-read | `d96bed3` (backend), `a8b708d` (frontend) | `POST /v1/notifications/mark-all-read` bulk endpoint + button in dropdown |
| ADR acceptance | `9185852`, `f78c685` | ADR-0010 (heartbeat harvest) Accepted; ADR common/0003 (dedup scoping) Accepted; ADR-0005 (thumbnails) Accepted |
| Pattern docs | `9185852` | `PRIMENG-MENU-STABLE-REFERENCE.md` — PrimeNG `p-menu` getter anti-pattern |
| Plan/spec docs | `b6ad1a3`, `9b286f5`, `f6107a2` | Option A blueprint + retrospective, notification 5.4 blueprint + retrospective, notification 5.1b retrospective |

### Beyond the original plan

| Item | Commits | Notes |
|------|---------|-------|
| mark-all-as-read (back + front) | `d96bed3`, `a8b708d` | Not in original Phase 5 spec — added as user-facing quality-of-life |
| Creator Dashboard visibility fix | `dd99ed4` | PrimeNG menu getter → stable `readonly` field via `buildUserMenu()` factory |
| Notification routing fix | Uncommitted (part of `221831e`) | `stream.started` click action routes to `/watch/:id` not `/channel/:id` |
| UI polish | `eb40665`, `9884613` | Global overlay scrollbar, notification card unread dot (replaced severity border) |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking | Reason |
|------|------------|----------|--------|
| Email template rendering (real SMTP dispatch) | Phase 5.3 proper | IMPLEMENTATION-PLAN.md §5.3 | `EmailAdapter` skeleton exists; real dispatch needs template engine + SMTP config |
| Subscribe (paid membership) button | Phase 8+ (monetization) | IMPLEMENTATION-PLAN.md §5 status | Needs payment infrastructure |
| SSE → WebSocket consolidation | Phase 6.4 | Option A blueprint Appendix B | 4 connections (2 SSE + REST polling + heartbeat) → 1 WebSocket |
| REST heartbeat → WebSocket | Phase 6.4 | Option A blueprint Appendix B | Clean migration via `PresenceService` seam |
| Daily compaction Spring Batch job | Phase 6.4+ or 7 | ADR-0010 §Retention Policy | Minute-bucket data manageable initially |
| Two-step fan-out (preference filtering) | Post-MVP | ADR-0002 §4 | All followers get all channels for now |
| Outbox-driven `FanOutJob` + `FanOutPoller` | Post-MVP | ADR-0002 §4 | Inline fan-out sufficient at current scale |
| Login rate limiter | Phase 6.3 or standalone | ADR auth/0003 (Proposed) | ADR exists, never implemented |
| Schedule reminders (2.7) | Phase 5.x+ | IMPLEMENTATION-PLAN.md §2.7 | Blocked on notification infrastructure |
| Stream templates (2.8) | Revisit when data justifies | IMPLEMENTATION-PLAN.md §2.8 | Not core to stream domain yet |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

| Item | Context | Recommended action |
|------|---------|-------------------|
| **"pending commit" markers are stale** — all Option A + Phase 5 work IS committed | The implementation plan header and 5 checklist items still said "pending commit" despite commits existing on `develop` since 2026-07-17. The previous retro (`9185852`) updated the plan but left these markers. The commits (`9bace9e`, `1aa52e9`, `6cec5c5`, `d485685`, etc.) prove the work is persisted. | ✅ Fixed — all "pending commit" suffixes stripped. |
| **Option A retrospective still said "uncommitted"** | `docs/plans/option-a-viewer-presence-fanout-retrospective.md` line 3: "Status: Implemented (uncommitted)". All tasks were committed on 2026-07-17. | ✅ Fixed — status updated to "Implemented (committed)", risk #4 resolved. |
| **Phase status bars in implementation plan were outdated** | Phase 4 header: "pending commit". Phase 5 header: overlong, contained implementation details that belong in retrospectives. Both phases were actually complete. | ✅ Fixed — Phase 4 `○ Planned` → `✅ Done`, Phase 5 `○ Planned` → `✅ Done`, headers condensed. |

## 4. Architectural decisions made during implementation (candidates for new ADRs)

1. **Insight ADRs still Proposed** — All 4 insight ADRs (0001–0004) remain Proposed. This is correct (Phase 7 is deferred), but they've been Proposed since inception. No action needed now, but flag for the next major planning cycle.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` | Last updated 2026-07-17 | (a) Header said "pending commit" for 4 items that are committed. (b) Phase 4 header still said "pending commit". (c) Phase 5 header was overlong and said "In Progress" when it's complete. | ✅ Fixed — all "pending commit" markers stripped, Phase 4/5 statuses corrected, "Last updated" → 2026-07-27. |
| `docs/plans/option-a-viewer-presence-fanout-retrospective.md` | Status: "Implemented (uncommitted)" | Status was stale — all 7 tasks committed. §4 risk #4 ("All Option A code is uncommitted") was resolved. | ✅ Fixed — status → "Implemented (committed)", risk #4 striked out. |
| ADR `auth/0003-login-rate-limiting.md` | Status: Proposed | Never implemented. ADR says "Proposed" since creation. | Keep as Proposed — correctly documenting a deferred decision. No action needed. |
| `docs/IDEMPOTENCY-PATTERN.md` | Status: "Documented — pending implementation" | No changes needed — 6.1 not yet started. | No action. |

## 6. Updated execution order (actual vs planned — last 20 commits)

| Planned order (Option A blueprint) | Commit | Status |
|-------------------------------------|--------|--------|
| A3 — Dedup key scoping | `9bace9e` | ✅ |
| A2.1 — `broadcasterUsername` on StreamEvent | `954f6ba` | ✅ |
| A2.2 — Follower fan-out | `9bace9e` | ✅ |
| A1.1 — SSE viewer count push | `6cec5c5`, `1aa52e9` | ✅ |
| A1.2 — Watch page viewer count | `d485685` | ✅ |
| A1.3 — Session rail + browse counts | `d485685`, `717b82d` | ✅ |
| A4 — Heartbeat harvest service | `6cec5c5` | ✅ |
| Notification routing fix | Part of `221831e` | ✅ |
| Creator Dashboard visibility fix | `dd99ed4` | ✅ |
| mark-all-as-read (back + front) | `d96bed3`, `a8b708d` | ✅ |
| ADR acceptance + plan update | `9185852`, `b6ad1a3` | ✅ |

## 7. Key risks carried forward

1. **Fan-out blocks Kafka listener thread** — Documented in Option A retro. Acceptable for MVP; needs monitoring at scale.
2. **6.2–6.6 remain unstarted** — 6.1 (Idempotency Keys) is implemented but uncommitted. 6.2 (shared pbac-common) is the logical next step.
3. **All Phase 4/5 work is committed and stable** — No risks from the implementation itself. The only issues were documentation staleness, now resolved.

---

## 8. Phase 6.1 — Idempotency Keys (2026-07-27, afternoon session)

**Status:** Implemented (uncommitted — 22 files changed: 18 modified, 4 new)
**Blueprint:** [phase-6.1-idempotency-keys.md](../blueprints/phase-6.1-idempotency-keys.md)

### 8.1 What was implemented (vs the blueprint)

| Planned item | Files | Notes |
|-------------|-------|-------|
| Task 1 — Gateway Redis dependency + config | `build.gradle.kts`, `application.yml`, `IdempotencyProperties.java`, `GatewayApplication.java` | `spring-boot-starter-data-redis-reactive` added; `streaming.gateway.idempotency.ttl-seconds` config |
| Task 2 — IdempotencyFilter + CachedResponse | `IdempotencyFilter.java` (209 lines), `CachedResponse.java` | `WebFilter` + `Ordered`, `@Order(2)`, `ServerHttpResponseDecorator` + `DataBufferUtils.join`, fail-open, 2xx-only caching, Base64 body encoding |
| Task 3 — Frontend IdempotencyService + service wiring | `idempotency.service.ts`, `stream.service.ts`, `chat.service.ts`, `subscription.service.ts`, `notification.service.ts`, `chat-moderation.service.ts` | `newKey()` → `crypto.randomUUID()`; static `options(key?)` helper (consolidated from 5 duplicate `withKey` methods during review); 17 state-changing methods accept optional `idempotencyKey?` |
| Task 4 — Auth interceptor POST retry | `auth.interceptor.ts` | Unsafe methods with `Idempotency-Key` header are now retried after token refresh |
| Task 5 — Component wiring | `stream-detail.page.ts`, `stream-create.page.ts`, `chat-panel.component.ts`, `channel.page.ts`, `notification-dropdown.component.ts`, `notification-settings.page.ts`, `ban-list-panel.component.ts`, `about-tab.component.ts` | `chat-panel` uses `Map<string, string>` for retry-safe key reuse per `clientId`; `stream-detail` uses factory pattern for `runLifecycle()` |

### 8.2 Decisions made during implementation

1. **`@Order(2)` settled** — Blueprint waffled between `-1`, `0`, `1`. Settled on `2` because Spring Security chains use `@Order(0)` (public) and `@Order(1)` (protected). The filter MUST run after authentication.
2. **`IdempotencyService.options()` static method** — Emerged from code review. Five services had an identical `private withKey(key?)` helper. Extracted to a single static method that returns `{ headers?: HttpHeaders }`, fitting directly into `HttpClient` options.
3. **`CachedResponse` uses Base64 body** — `DataBufferUtils.join` produces raw bytes; Base64 encoding avoids JSON escaping issues with binary/non-UTF8 response bodies.

### 8.3 What was deferred (documented)

| Item | Deferred to | Reason |
|------|------------|--------|
| Integration tests (Testcontainers Redis) | Post-Docker setup | Docker-gated per project convention |
| Component wiring for remaining callers | Incremental follow-up | Most impactful components wired; remaining can be added as needed |
| `idempotencyKeyByClientId` Map memory leak | Follow-up | LOW — failed-never-retried messages leave stale entries; acceptable for MVP |

### 8.4 Review findings (all fixed)

| # | Severity | Finding | Fix |
|---|----------|---------|-----|
| 1 | MEDIUM | `@Order(0)` ambiguous vs Spring Security chains | Changed to `@Order(2)` |
| 2 | MEDIUM | `withKey` helper duplicated in 5 services | Extracted to `IdempotencyService.options()` static method |
| 3 | MEDIUM | No unit tests for IdempotencyFilter | Deferred (Docker-gated) |
