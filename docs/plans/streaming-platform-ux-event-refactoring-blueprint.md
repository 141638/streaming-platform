# Blueprint: Streaming Platform UX & Event Refactoring

**Date**: 2026-07-15
**Status**: Plan — awaiting implementation
**Branch**: develop

## Summary

Seven interconnected changes to improve viewer and streamer UX after the full OBS→SRS→client flow is working. Covers navigation fixes, watchdog debugging, draft-stream routing, ended-stream watch support with configurable chat archival, SSE lifecycle events for live status synchronization, browse page multi-rail layout, and watch history tracking.

## Task Dependency Graph

```
Phase A (Independent — can ship in parallel):
  T1 (video-tab nav fix)         — 1 file, frontend only
  T2 (watchdog logs)             — 1 file, ops only
  T3 (create→draft route)        — 5 files, backend + frontend

Phase B (Backend Infrastructure):
  T5 (SSE stream events)         — 6 files, backend + frontend
                                   unblocks T4, T6

Phase C (Depends on Phase B):
  T4 (ended watch + chat archive) — 12 files, DB + backend + frontend
  T6 (browse rails)              — 4 files, backend + frontend
  T7 (watch history)             — 8 files, DB + backend + frontend
```

**Recommended execution order**: T1, T2, T3 (parallel) → T5 → T4 → T6, T7 (parallel)

## Detailed Task Documents

| Task | Document | Scope |
|------|----------|-------|
| T1 | [T1-video-tab-navigation-fix.md](T1-video-tab-navigation-fix.md) | Frontend |
| T2 | [T2-srs-thumbnail-watchdog-logging.md](T2-srs-thumbnail-watchdog-logging.md) | DevOps |
| T3 | [T3-draft-stream-creation-routing.md](T3-draft-stream-creation-routing.md) | Backend + Frontend |
| T4 | [T4-ended-stream-watch-chat-archive.md](T4-ended-stream-watch-chat-archive.md) | DB + Backend + Frontend |
| T5 | [T5-sse-stream-lifecycle-events.md](T5-sse-stream-lifecycle-events.md) | Backend + Frontend |
| T6 | [T6-browse-page-multi-rail.md](T6-browse-page-multi-rail.md) | Backend + Frontend |
| T7 | [T7-watch-history.md](T7-watch-history.md) | DB + Backend + Frontend |

## Patterns to Mirror (cross-cutting)

| Category | Source File | Pattern |
|----------|-------------|---------|
| SSE | `notification-service:.../NotificationSseController.java` | Flux<ServerSentEvent> + SseConnectionRegistry concurrent map |
| SSE frontend | `notification.service.ts:connect()` | `@microsoft/fetch-event-source` with AbortController |
| Outbox events | `stream-service:.../OutboxWriter.java` | Write to outbox in same TX, poller publishes to Kafka |
| Stream state machine | `StreamSessionEntity.java:transitionTo()` | Status transitions with timestamp management |
| DTO records | `StreamResponse.java` | Java records with `static from(Entity)` factory |
| Angular routing | `app.routes.ts` | Lazy-loaded standalone components with `CanMatch` guards |
| Reactive forms | `stream-create.page.ts` | FormBuilder + Validators |
| Signal state | `watch.page.ts` | `signal()` + `computed()` + OnPush |
| PBAC authz | `StreamAuthorization.java` | `RequiredAuthority` + `requireAccess()` |
| Flyway migrations | `V1-V13` stream, `V1-V6` chat | Versioned SQL with IF NOT EXISTS |
| Angular conventions | `angular-coding-conventions.md` memory | Component member ordering, modifier rules |

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| SSE connection scaling (many viewers) | Medium | Follow notification SSE pattern (connection registry per user); same architecture scales |
| Watchdog still fails silently after logging | Low | Logs will show WHERE it fails (curl vs ffmpeg vs permissions) |
| Stream key in navigation state lost on refresh | Low | After initial display, user copies key; refresh falls back to masked key from GET |
| Ended stream HLS URL no longer valid | High | Watch page catches HLS errors; stops player on ENDED, shows poster |
| Chat archival timing race conditions | Medium | DB transaction + idempotent archive operation (check status before archiving) |

## Validation Commands

```bash
# Backend build
cd main/source/backend && ./gradlew build

# Frontend build
cd main/source/frontend/streaming-ui && npm run build

# SRS container logs (for T2 watchdog verification)
docker logs srs 2>&1 | grep watchdog
```
