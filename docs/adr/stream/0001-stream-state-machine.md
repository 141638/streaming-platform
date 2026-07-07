# ADR-0001: Stream State Machine

**Status:** Accepted (Revised 2026-07-07; implemented 2026-07-08)
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

Streams pass through lifecycle states: draft, scheduled, live, ended, cancelled. Prior to Phase 2.3, `StreamStatus` was a plain enum with no transition validation, and `StreamService.updateStream()` accepted any status change via raw string input.

During Phase 2.3 implementation, SCHEDULED was initially modeled as a state reachable from DRAFT, with transitions to LIVE and CANCELLED. Subsequent architecture review for SRS integration (Phase 2.4) revealed that SCHEDULED should be a **terminal planning state** — it represents a future broadcast intent, not an active stream. A scheduled stream that goes live transitions back to DRAFT (with a fresh publish key) via a dedicated go-live action, not a generic state transition.

## Decision

Implement the state machine as **rich domain methods on `StreamSessionEntity`**, with transition rules defined on the `StreamStatus` enum using Java 21 switch expressions.

### Transition Map (Revised)

```
DRAFT     ──→ LIVE, CANCELLED
SCHEDULED ──→ DRAFT (only via dedicated go-live action — not a generic transition)
LIVE      ──→ ENDED
ENDED     ──→ (terminal)
CANCELLED ──→ (terminal)
SCHEDULED ──→ (terminal — except for the go-live action)
```

### Semantic Boundary (Revised)

- **DRAFT** = prepared state. Streamer has created the stream, obtained a publish key, and is setting up OBS. Cancellable. The primary path to LIVE is via SRS webhook (OBS starts streaming), with a manual `/start` endpoint for testing.
- **SCHEDULED** = planned future broadcast. **No publish key is issued** — the streamer cannot publish until they explicitly convert it to a DRAFT via the go-live action. Terminal except for go-live. Used for subscriber notifications (future Phase) and pre-stream reminders.
- **LIVE** = active broadcast. Can only be ended (via SRS `on_unpublish` webhook or manual `/end`).
- **ENDED** = completed broadcast. Immutable terminal record.
- **CANCELLED** = dropped plan (DRAFT only). Immutable terminal record.

### SCHEDULED → DRAFT (Go-Live Action)

The go-live action is NOT a generic transition. It is a dedicated service method that:
1. Validates the stream is in SCHEDULED status
2. Clears `scheduledAt`
3. Issues a fresh publish key (SRS name + JWT publish token)
4. Sets status to DRAFT
5. Returns the new publish URL to the streamer

This avoids cloning/duplication while keeping the schedule as a reusable plan. A schedule can be "activated" multiple times (for recurring broadcasts).

### From Phase 2.3 to Phase 2.4 — What Was Removed

| Removed | Reason |
|---------|--------|
| `DRAFT → SCHEDULED` transition | SCHEDULED is now a creation-time state only (via `CreateStreamRequest.scheduledAt`) |
| `SCHEDULED → LIVE` transition | SCHEDULED cannot go directly live — must go through DRAFT first |
| `SCHEDULED → CANCELLED` transition | SCHEDULED is terminal; cancel by going to DRAFT first then cancelling |
| `entity.schedule()` domain method | No longer needed |
| `StreamService.scheduleStream()` | No longer needed |
| `POST /v1/streams/{id}/schedule` endpoint | No longer needed |
| `ScheduleStreamRequest` DTO | No longer needed |

### What Was Kept (Manual Testing Endpoints)

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/streams/{id}/start` | Manual DRAFT→LIVE for testing without OBS/SRS |
| `POST /v1/streams/{id}/end` | Manual LIVE→ENDED for testing |
| `POST /v1/streams/{id}/cancel` | Manual DRAFT→CANCELLED for testing |

### What Was Added (Phase 2.4)

| Endpoint | Purpose |
|----------|---------|
| `POST /v1/streams/{id}/go-live` | SCHEDULED→DRAFT activation with fresh publish key (see ADR-0004) |

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Spring State Machine | Rejected | Heavy dependency; poor reactive support; overkill for 5-state model |
| Separate `StreamStateMachine` service | Rejected | Anemic entity model; splits mutation logic across files |
| Entity domain methods | **Accepted** | Behavior lives with data; single source of truth; aligns with DDD layered architecture |
| Clone schedule → new DRAFT stream | Rejected | Creates entity duplication; SCHEDULED→DRAFT transition is cleaner |

### Optimistic Concurrency

`@Version Long version` on `StreamSessionEntity`. Spring Data R2DBC translates to `UPDATE WHERE id=? AND version=?`. Stale writes fail with `OptimisticLockingFailureException`.

### One-Live-Stream Per Broadcaster

Enforced at two tiers:
1. **Service layer**: `repository.existsByBroadcasterSubjectAndStatus(sub, LIVE)` before `goLive()` — provides user-friendly 409 error
2. **Database**: Partial unique index `WHERE status = 'live'` on `(broadcaster_subject)` — absolute guard against races between concurrent start requests

### PBAC

All lifecycle transitions use `AuthAction.LIFECYCLE`. Each method follows: `find entity → authorize → validate → transition → save → publish`.

## Consequences

- **Positive**: Transitions are validated at the domain layer
- **Positive**: `@Version` prevents stale writes without distributed locks
- **Positive**: Partial unique index provides database-level invariant enforcement
- **Positive**: SCHEDULED as terminal simplifies the transition map (fewer paths to reason about)
- **Negative**: `@Setter`-generated `setStatus()` still exists on the entity; discipline required
- **Negative**: `OptimisticLockingFailureException` requires client retry logic
- **Negative**: Go-live action (SCHEDULED→DRAFT) is not expressible in `StreamStatus.allowedTransitions()` — it's a dedicated service method, not a generic transition

## References

- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md)
- [ADR-0003: Categories and Tags](0003-categories-tags.md)
- [ADR-0004: SRS Webhook Integration & Publish Token Architecture](0004-srs-webhook-publish-token.md)
- `StreamStatus.java` — transition rule implementation
- `StreamSessionEntity.java` — domain methods `transitionTo()`, `goLive()`, `end()`, `cancel()`
