# ADR-0001: Stream State Machine

**Status:** Accepted
**Date:** 2026-07-07
**Domain:** Stream Service

## Context

Streams pass through lifecycle states: draft, scheduled, live, ended, cancelled. Prior to Phase 2.3, `StreamStatus` was a plain enum with no transition validation, and `StreamService.updateStream()` accepted any status change via raw string input. We needed a state machine that enforces valid transitions and publishes lifecycle events.

## Decision

Implement the state machine as **rich domain methods on `StreamSessionEntity`**, with transition rules defined on the `StreamStatus` enum using Java 21 switch expressions.

### Transition Map

```
DRAFT     ──→ SCHEDULED, LIVE, CANCELLED
SCHEDULED ──→ LIVE, CANCELLED
LIVE      ──→ ENDED
ENDED     ──→ (terminal)
CANCELLED ──→ (terminal)
```

### Semantic Boundary

- **DRAFT + SCHEDULED** = planned states. These represent *intent* and are cancellable.
- **LIVE** = active state. This represents *execution* and can only be ended.
- **ENDED** = completed broadcast. Immutable terminal record.
- **CANCELLED** = dropped plan. Immutable terminal record.

This boundary gives meaning to the DRAFT/SCHEDULED distinction — they are two flavors of "not yet live," and both are disposable. Once live, cancellation is semantically wrong; the stream must be ended.

### Alternatives Considered

| Approach | Verdict | Reason |
|----------|---------|--------|
| Spring State Machine | Rejected | Heavy dependency; poor reactive support; overkill for 5-state model |
| Separate `StreamStateMachine` service | Rejected | Anemic entity model; splits mutation logic across files |
| Entity domain methods | **Accepted** | Behavior lives with data; single source of truth; aligns with DDD layered architecture |

### Optimistic Concurrency

`@Version Long version` on `StreamSessionEntity`. Spring Data R2DBC translates to `UPDATE WHERE id=? AND version=?`. Stale writes fail with `OptimisticLockingFailureException`. Prevents:
- Two operators simultaneously starting the same stream
- A `goLive()` racing against a `cancel()` on the same entity
- Out-of-order transitions in multi-instance deployments

### One-Live-Stream Per Broadcaster

Enforced at two tiers:
1. **Service layer**: `repository.existsByBroadcasterSubjectAndStatus(sub, LIVE)` before `goLive()` — provides user-friendly 409 error
2. **Database**: Partial unique index `WHERE status = 'live'` on `(broadcaster_subject)` — absolute guard against races between concurrent start requests

### PBAC

All lifecycle transitions use `AuthAction.LIFECYCLE` (already defined in the PBAC framework). Each lifecycle method follows the identical pattern: `find entity → authorize(LIFECYCLE) → validate → transition → save → publish`.

## Consequences

- **Positive**: Transitions are validated at the domain layer, not scattered across service methods
- **Positive**: `@Version` prevents stale writes without distributed locks
- **Positive**: Partial unique index provides database-level invariant enforcement
- **Negative**: `@Setter`-generated `setStatus()` still exists on the entity; discipline required to avoid bypassing transition methods
- **Negative**: `OptimisticLockingFailureException` requires retry logic in the client (acceptable for Phase 2)

## References

- [ADR-0002: Kafka Event Publishing](0002-kafka-event-publishing.md)
- [ADR-0003: Categories and Tags](0003-categories-tags.md)
- `StreamStatus.java` — transition rule implementation
- `StreamSessionEntity.java` — domain methods `transitionTo()`, `goLive()`, `end()`, `cancel()`, `schedule()`
