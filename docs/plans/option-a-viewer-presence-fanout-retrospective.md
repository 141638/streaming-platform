# Option A — Viewer Presence + Fan-out + Dedup + Harvest (Design Session Retrospective)

**Date:** 2026-07-17
**Status:** Plan approved — awaiting implementation
**Session type:** Architecture design + blueprint (no code written)

## 1. What was designed (scope of this session)

| Item | Artifact | Notes |
|------|----------|-------|
| Viewer count display (A1) | Blueprint A1.1–A1.3 | SSE push + watch page badge + session card counts |
| Follower fan-out (A2) | Blueprint A2.1–A2.2 | `StreamControlListener.onStreamStarted()` fan-out + `broadcasterUsername` on `StreamEvent` |
| Dedup key scoping (A3) | Blueprint A3.1 | `dedup:{topic}:{consumerGroupId}:{eventId}` per ADR common/0003 |
| Heartbeat harvest service (A4) | Blueprint A4 + ADR-0010 | Minute-bucket aggregation from Redis presence keys, stream-service owned |
| Phase 6.4 WebSocket sketch | Blueprint Appendix B | Single WS per user: chat + heartbeat + stream status + notifications |

## 2. Architectural decisions made

1. **Heartbeat harvesting → stream-service** (not insight-service). Rationale: data proximity (Redis keys are in stream-service), existing precedent (`ViewCountFlushService`), insight-service doesn't exist yet. Documented in ADR-0010.

2. **SSE consolidation → deferred to Phase 6.4**. Rationale: WebSocket unification solves this at the protocol level — building a temporary SSE consolidation layer would be throwaway work.

3. **REST heartbeats → keep for now, migrate to WebSocket in 6.4**. Rationale: `PresenceService` is cleanly abstracted — migration is a one-line transport change. The HTTP overhead is acceptable at current POC scale.

4. **Inline fan-out for MVP** (not outbox-driven). Rationale: `NotificationDispatcher.deliverToMany()` already exists. Outbox-driven `FanOutJob` deferred until subscriber counts warrant it. Per ADR-0002 §4.

5. **Minute-bucket aggregation, not per-user storage**. Rationale: 12M raw heartbeats per stream per peak would burst the database. One row per stream per minute = ~1.4M rows/day for 1,000 active streams — manageable.

6. **`broadcasterUsername` on `StreamEvent`** (Option A from blueprint). Rationale: Java record with nullable field is backward-compatible with JSON deserialization. Cleaner than cross-service HTTP calls or frontend-side resolution.

## 3. What was deferred (documented, with tracking)

| Item | Deferred to | Tracking | Reason |
|------|------------|----------|--------|
| SSE connection consolidation (1 per user) | Phase 6.4 | Blueprint Appendix B | WebSocket unification solves this |
| REST heartbeat → WebSocket | Phase 6.4 | Blueprint Appendix B | Clean migration path via `PresenceService` |
| Daily compaction Spring Batch job | Phase 6.4+ or 7 | ADR-0010 §Retention Policy | Minute-bucket data is manageable initially |
| Two-step fan-out (preference filtering) | Post-MVP | ADR-0001 §4, ADR-0002 §4 | All followers get all channels for now |
| Outbox-driven `FanOutJob` + `FanOutPoller` | Post-MVP | ADR-0002 §4 | Inline fan-out sufficient at current scale |
| `ReactiveNotificationPreferenceRepository` | Post-MVP | Blueprint A2 notes | Preference entity exists; repository not yet created |

## 4. Documents created or updated this session

### Created

| File | Type | Purpose |
|------|------|---------|
| `docs/adr/stream/0010-viewer-heartbeat-analytics-pipeline.md` | ADR (Proposed) | Heartbeat harvesting design: minute-bucket aggregation, stream-service ownership, retention policy |
| `docs/plans/option-a-viewer-presence-fanout-blueprint.md` | Blueprint | Implementation plan for A1–A4 with ~20 files, patterns to mirror, risk assessment, execution order |

### Updated

| File | What changed |
|------|-------------|
| `docs/IMPLEMENTATION-PLAN.md` | Header date/phase/bluerprint; Phase 4 checklist (+4.4b heartbeat harvest); Phase 5 checklist (fan-out + dedup scoping detail) |
| `docs/adr/stream/README.md` | Added ADR-0009 (outbox) + ADR-0010 (heartbeat harvest) to index; fixed ADR-0005 status Proposed→Accepted |
| `docs/adr/insight/0001-insight-service-architecture.md` | Added upstream data sources table (`stream_viewer_snapshot` + others); added reference to ADR-0010 |

## 5. Survey findings (Phase 1 substance gate)

### Discovery: Viewer presence is ~80% complete

The survey revealed that `sendHeartbeat()`, `getViewerCount()`, `StreamSseController`, `SseConnectionRegistry`, `StreamSseEvent.viewerCount`, `PresenceService`, `StreamSseService`, and watch page integration are all already built. Only the SSE push wiring and frontend display remain.

### Discovery: Fan-out is ~60% complete

`SubscriptionRepository.findByTargetTypeAndTargetIdAndActiveTrue()`, `NotificationDispatcher.deliverToMany()`, and `Notification.create()` all exist. Only the wiring in `StreamControlListener.onStreamStarted()` and the `broadcasterUsername` field are missing.

### Stale doc fix: ADR-0005 status

`docs/adr/stream/README.md` listed ADR-0005 (thumbnails) as "Proposed" — the actual ADR file says "Accepted" and thumbnails were implemented 2026-07-15. Fixed.

### Database-design compliance: NA

No Flyway migrations or R2DBC entities were touched this session (pure planning).

## 6. Key risks carried forward

1. **Fan-out blocks Kafka listener thread** — mitigated by `flatMap` concurrency 8 on bounded elastic + `blockOptional(10s)`. Acceptable for MVP follower counts.
2. **`StreamEvent` field addition breaks chat-service** — mitigated by Java record backward compatibility (nullable field, JSON unknown-field handling).
3. **Harvest UPSERT contention at scale** — mitigated by `GREATEST()` on UPSERT + minute-bucket granularity (1.4M rows/day for 1,000 streams).
4. **12M heartbeats/min at peak** — mitigated by aggregation at source; raw heartbeats never touch the database.
