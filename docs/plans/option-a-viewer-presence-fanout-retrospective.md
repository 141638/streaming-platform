# Option A — Viewer Presence + Fan-out + Dedup + Harvest (Implementation Retrospective)

**Date:** 2026-07-17
**Status:** Implemented (uncommitted) — all 7 tasks complete, 2 bug fixes applied, + mark-all-as-read add-on
**Session type:** Architecture design + full implementation + bug fixes

## 1. What was implemented (vs the original plan)

| Planned item | Status | Files | Notes |
|-------------|--------|-------|-------|
| A3 — Dedup key scoping | ✅ Done | `StreamControlListener.java` (modified) | Changed `dedup:stream-event:{eventId}` → `dedup:{topic}:{consumerGroupId}:{eventId}` per ADR common/0003 |
| A2.1 — `broadcasterUsername` on `StreamEvent` | ✅ Done | `common/.../StreamEvent.java` (modified), `StreamService.java` (modified, 7 call sites) | Added nullable field between `broadcasterSubject` and `autoArchiveChat`; updated all 6 factory methods |
| A2.2 — Follower fan-out | ✅ Done | `StreamControlListener.java` (modified), `NotificationService.java` (modified) | `getSubscribers()` → `createForFollower()` → `deliverToMany(notifications, 8)` |
| A1.1 — SSE viewer count push | ✅ Done | `ViewerCountPushService.java` (new), `SseConnectionRegistry.java` (modified) | `@Scheduled(fixedRate=10000)` SCANs presence keys → pushes `StreamSseEvent("stream:viewers", ...)` |
| A1.2 — Watch page viewer count | ✅ Done | `watch.page.ts` (modified), `watch.page.html` (modified) | Eye icon + `\| number` pipe in watch-info-bar, gated by `isLive()` |
| A1.3 — Session rail + browse viewer counts | ✅ Done | `session-rail.component.ts/html` (modified), `browse.page.html` (modified), `stream-summary-response.dto.ts` (modified) | Live badge with concurrent count + eye icon, fallback to `views`; `viewerCount` on DTO |
| A4 — Heartbeat harvest service | ✅ Done | `HeartbeatHarvestService.java` (new), `StreamViewerSnapshotEntity.java` (new), `StreamViewerSnapshotRepository.java` (new), `HeartbeatHarvestProperties.java` (new), `V16__create_stream_viewer_snapshot.sql` (new), `application.yml` (modified), `StreamApplication.java` (modified) | `@Scheduled(fixedDelay=30000)` SCANs → groups → `GREATEST()` UPSERT, concurrency 8 |

### Beyond the plan

| Item | Commits | Notes |
|------|---------|-------|
| Notification routing fix | Uncommitted | Changed `actionFromNotification()` route from `/channel/${streamId}` → `/watch/${streamId}` |
| Creator Dashboard visibility | Uncommitted | Converted `userMenuItems` from getter to stable `readonly` field via `buildUserMenu()` factory; conditionally includes "Creator Dashboard" only for streamers |
| mark-all-as-read (backend) | `d96bed3` | `POST /v1/notifications/mark-all-read` bulk endpoint — `NotificationController.markAllAsRead()`, `NotificationService.markAllAsRead()`, `ReactiveNotificationRepository.markAllAsReadBySubject()` |
| mark-all-as-read (frontend) | `a8b708d` | Button in notification dropdown, `NotificationService.markAllAsRead()` HTTP call |

## 2. What was deferred (documented, with tracking)

| Item | Deferred to | Tracking | Reason |
|------|------------|----------|--------|
| SSE connection consolidation (1 per user) | Phase 6.4 | Blueprint Appendix B | WebSocket unification solves this |
| REST heartbeat → WebSocket | Phase 6.4 | Blueprint Appendix B | Clean migration path via `PresenceService` |
| Daily compaction Spring Batch job | Phase 6.4+ or 7 | ADR-0010 §Retention Policy | Minute-bucket data is manageable initially |
| Two-step fan-out (preference filtering) | Post-MVP | ADR-0001 §4, ADR-0002 §4 | All followers get all channels for now |
| Outbox-driven `FanOutJob` + `FanOutPoller` | Post-MVP | ADR-0002 §4 | Inline fan-out sufficient at current scale |
| `ReactiveNotificationPreferenceRepository` | Post-MVP | Blueprint A2 notes | Preference entity exists; repository not yet created |

## 3. What was deferred but NOT yet documented (gaps found during retro)

| Item | Context | Recommended action |
|------|---------|-------------------|
| _None found_ | — | All deferrals are documented in the blueprint + ADRs |

## 4. Architectural decisions made during implementation

1. **Heartbeat harvesting → `@Scheduled` not Spring Batch** (recorded in blueprint). Rationale: Spring Batch would be over-engineered for a 30-second SCAN + UPSERT job. A simple `fixedDelayString` with configurable interval is sufficient. Daily compaction (minute → hourly → daily) may warrant Spring Batch later.

2. **`broadcasterUsername` on `StreamEvent`** (Option A from blueprint). Java record with nullable field is backward-compatible — `@JsonIgnoreProperties(ignoreUnknown = true)` on consumers means existing services ignore unknown fields, and a null default means old producers (without the field) are handled gracefully at new consumers.

3. **Inline fan-out for MVP** (not outbox-driven). `NotificationDispatcher.deliverToMany()` already exists. Outbox-driven `FanOutJob` deferred until subscriber counts warrant it. Per ADR-0002 §4.

4. **PrimeNG `p-menu` requires stable array reference** — discovered during Creator Dashboard fix. Getters returning new arrays on every change detection cycle cause re-render flash and broken clicks. Pattern: use `readonly` field initialized once via a standalone factory function. See [PRIMENG-MENU-STABLE-REFERENCE.md](../PRIMENG-MENU-STABLE-REFERENCE.md).

5. **`GREATEST()` on heartbeat harvest UPSERT** — captures peak concurrent viewers within each minute bucket. `LEAST()` is NOT needed because the minute bucket is the finest granularity; dips within a minute are invisible by design (30s harvest period, 60s bucket).

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` | A1/A2/A3/A4 as unchecked | All 7 tasks implemented | Check 4.4, 4.4b, 5.2b dedup, 5.2b fan-out; update header status |
| `docs/adr/stream/0010-viewer-heartbeat-analytics-pipeline.md` | Status: proposed | Harvest service implemented | Accept ADR |
| `docs/adr/common/0003-cross-service-event-dedup-key-scoping.md` | Status: proposed | Dedup scoping implemented | Accept ADR |
| This retrospective | Status: "awaiting implementation" | All tasks implemented | Updated (this file) |

## 6. Updated execution order (actual vs planned)

| Planned order | Actual order | Status |
|--------------|-------------|--------|
| A3 — Dedup key scoping | A3 first | ✅ |
| A2.1 — `broadcasterUsername` | A2.1 second | ✅ |
| A2.2 — Follower fan-out | A2.2 third | ✅ |
| A1.1 — SSE viewer count push | A1.1 fourth | ✅ |
| A1.2 — Watch page viewer count | A1.2 fifth | ✅ |
| A1.3 — Session rail + browse viewer counts | A1.3 sixth | ✅ |
| A4 — Heartbeat harvest service | A4 seventh | ✅ |
| _(not planned)_ | Notification routing fix | ✅ |
| _(not planned)_ | Creator Dashboard visibility fix | ✅ (2 iterations — getter → stable field) |
| _(not planned)_ | mark-all-as-read (back + front) | ✅ Committed: `d96bed3`, `a8b708d` |

## 7. Key risks carried forward

1. **Fan-out blocks Kafka listener thread** — mitigated by `flatMap` concurrency 8 on bounded elastic + `blockOptional(10s)`. Acceptable for MVP follower counts.
2. **`StreamEvent` field addition breaks downstream consumers** — mitigated by Java record backward compatibility (nullable field, `@JsonIgnoreProperties(ignoreUnknown = true)`).
3. **Harvest UPSERT contention at scale** — mitigated by `GREATEST()` on UPSERT + minute-bucket granularity.
4. **All Option A code is uncommitted** — working tree changes across 23 files. Must be committed before switching branches or merging.
