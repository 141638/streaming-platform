# Phase C4 — Fire-and-Forget Lifecycle Fix Retrospective

**Date:** 2026-08-02
**Status:** Complete

## 1. What was implemented (vs the original plan)

| Planned item | Status | Notes |
|-------------|--------|-------|
| HeartbeatHarvestService: `.subscribe()` → `.blockOptional(25s)` | ✅ Done | + `.then()` correction for Flux→Mono |
| ChatArchiveScheduler: `.subscribe()` → `.blockOptional(55s)` | ✅ Done | + `.then()` correction for Flux→Mono |
| ViewerCountPushService: `.subscribe()` → `.blockOptional(8s)` | ✅ Done | + `.then()` correction for Flux→Mono |
| ViewCountFlushService: `Mono<Void>` → `void` + `.blockOptional(290s)` | ✅ Done | + `.then()` correction for Flux→Mono; kept `.onErrorComplete()` (bulk flush: one bad key shouldn't abort) |
| ChatService x2: add `.subscribeOn(boundedElastic)` | ✅ Done | Both `getRecentMessages` and `getMessagesBefore` backfills |
| IdempotencyFilter: add `.subscribeOn(boundedElastic)` | ✅ Done | Cache-write fire-and-forget |
| Delete StreamEventPublisher | ✅ Done | Removed stale field/param from StreamService and StreamServiceTest |

## 2. What was deferred (documented, with tracking reference)

Nothing deferred — all planned items implemented.

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None.

## 4. Architectural decisions made during implementation

### Decision 1: `.then()` required before `.blockOptional()` on Flux chains

The plan specified `.blockOptional(Duration)` directly on the reactive chain, but these chains end in `Flux<T>` (via `flatMap`), not `Mono<T>`. `blockOptional` is a Mono-only method. `.then()` converts `Flux<T>` → `Mono<Void>`.

### Decision 2: Reactive scheduled-method error-binding pattern

`.subscribe()` runs the reactive chain on a daemon thread — errors are invisible to Spring's TaskScheduler. `.blockOptional()` binds the chain to the scheduler's own thread, so errors propagate as exceptions that Spring catches and logs with full stack traces. This pattern is now documented in [REACTIVE-SCHEDULED-METHOD-PATTERN.md](../REACTIVE-SCHEDULED-METHOD-PATTERN.md).

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/IMPLEMENTATION-PLAN.md` | Last updated 2026-08-01 | C4 not tracked in Phase C quick wins | Add C4 to Track E / quick-wins checklist |
| `docs/plans/C4-fire-and-forget-subscribe-fix.md` | "Planned" checked | Now implemented | Deleted — superseded by this retrospective |

## 6. Architecture & reference docs updated

| Document | Trigger | What changed |
|----------|---------|-------------|
| `docs/REACTIVE-SCHEDULED-METHOD-PATTERN.md` | New reusable pattern discovered | **Created** — captures the `.blockOptional()` vs `.subscribe()` rule for scheduled methods |

## 7. Key risks carried forward

1. **ViewCountFlushService still has `.onErrorComplete()`** — individual flushOne failures don't propagate. This is intentional (bulk flush resilience), but means Redis key corruption or DB schema drift could cause persistent silent skips. A future metrics counter for `flushOne` failures would close this gap.
