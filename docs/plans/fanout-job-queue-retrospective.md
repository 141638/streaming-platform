# Fan-Out Job Queue — Implementation Retrospective

**Date:** 2026-08-08
**Status:** Complete — all 8 tasks implemented, 11 unit tests passing

## 1. What was implemented (vs the original plan)

| Planned item | Files | Notes |
|-------------|-------|-------|
| Task 1: V6 Flyway migration | `V6__create_fanout_job_table.sql` | `notification.fan_out_job` table + 3 indexes (poll scan, dedup unique, claimed partial). All Tier 1 column types — no converters needed. |
| Task 2: FanOutJob entity | `FanOutJob.java` | Mirrors `OutboxEntry`/`Subscription`: `Persistable<UUID>`, `@Transient isNew`, static `create()`, 5 state constants (PENDING → PROCESSING → COMPLETED/FAILED/DEAD) |
| Task 3: ReactiveFanOutJobRepository | `ReactiveFanOutJobRepository.java` | `ReactiveCrudRepository` with `@Query` text blocks: `pollPending` (FOR UPDATE SKIP LOCKED), `claimJob`, `updateState`, `existsByEventIdAndJobType`, `countByState`, `findStuckJobs` |
| Task 4: FanOutService (enqueue) | `FanOutService.java` | `enqueue(event)`: dedup check → `FanOutJob.create` → save → log → `.then()`. Injects `ReactiveFanOutJobRepository`. |
| Task 5: FanOutPoller (worker) | `FanOutPoller.java` | `@Scheduled` worker: poll → claim → load subscribers → chunk → `deliverToMany` per chunk → ACK/NACK/DLQ. Mirrors `OutboxPoller` pattern (`.blockOptional()` + `onErrorResume`). Injects `SubscriptionService` (not `ReactiveSubscriptionRepository` directly). |
| Task 6: StreamControlListener mod | `StreamControlListener.java` | `onStreamStarted()`: inline `getSubscribers()`→`createForFollower()`→`deliverToMany()` replaced with `fanOutService.enqueue(event)`. Removed `SubscriptionService` and `NotificationDispatcher` fields. |
| Task 7: Config | `application.yml` | `notification.fanout.*` block (poll-interval, batch-size, max-concurrency, max-retries, processing-timeout-seconds) |
| Task 8: Unit tests | `FanOutServiceTest.java`, `FanOutPollerTest.java` | 11 tests: enqueue (3), empty poll (1), claim+process (1), claim race skip (1), no subscribers (1), chunked dispatch (1), progress recording (1), first-failure retry (1), max-retries→DEAD (1) |

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Visibility timeout / stuck-job recovery | Future iteration | [fanout-job-queue-blueprint.md](fanout-job-queue-blueprint.md) §Future Extensions | Design calls it out as future work; partial index `ix_fan_out_job_claimed` already in place |
| `findStuckJobs` repository method | Future iteration | Same as above | Method declared in repository but no poller logic uses it yet |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

None. All gaps were pre-identified in the blueprint.

## 4. Architectural decisions made during implementation (candidates for new ADRs)

No new architectural decisions. The implementation followed the detail design closely. Four deliberate deviations from the design doc were bug fixes, not architectural choices:

1. **`buildStreamEvent()` constructor fix** — Detail design had wrong positional arg ordering for `StreamEvent` record. Used direct constructor with correct field order instead of `StreamEvent.started()` factory (which would fail on `UUID.fromString(job.getTargetId())` since `targetId` is a JWT sub, not a stream UUID).
2. **`claimAndProcess()` race-condition fix** — Detail design ignored `claimJob` row count and processed jobs even when another worker won the atomic claim (0 rows updated). Fixed by gating dispatch on `updated > 0`.
3. **DEAD-state guard** — Final COMPLETED update gated on `state != DEAD` to prevent overwriting DLQ markers.
4. **Retry off-by-one fix** — `handleChunkFailure` restructured to mirror `OutboxPoller.handleFailure()` exactly, fixing an off-by-one in the retry count.

None of these rise to ADR or pattern-doc level — they're correctness fixes to the implementation, not framework choices or architectural trade-offs.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action taken |
|----------|---------------|-------------|--------------|
| `fanout-job-queue-blueprint.md` | Status: draft | Feature is implemented | Marked **Implemented** |
| `fanout-job-queue-detail-design.md` | Status: draft | Feature is implemented; deviations not captured | Marked **Implemented**, added deviations appendix |
| `IMPLEMENTATION-PLAN.md` | Phase 5 "inline for MVP" | Fan-out is now async via job queue | Updated Phase 5 description + work items + checklist |

## 6. Architecture & reference docs updated

None triggered. No new service, protocol, Kafka topic, or infrastructure change occurred.

## 7. Updated execution order (actual vs planned)

| Planned (detail design) | Actual | Status |
|--------------------------|--------|--------|
| Tasks 1-7: Sequential implementation | Single java-backend-developer agent, sequential | ✅ |
| Task 8: Tests after implementation | Separate agent after implementation compiled | ✅ |
| Build verification after Task 7 | `./gradlew :notification-service:compileJava` — BUILD SUCCESSFUL | ✅ |
| Test verification after Task 8 | `./gradlew :notification-service:test` — 11/11 passing | ✅ |

## 8. Key risks carried forward

1. **No integration tests** — 11 unit tests verify the reactive chains with mocked dependencies, but there's no end-to-end test with a real PostgreSQL instance. The `FOR UPDATE SKIP LOCKED` concurrency behavior and the unique-index dedup are tested only at the mock level. Mitigation: run a manual integration smoke test before deploying (insert a job → verify poller picks it up → verify subscribers get notifications).
2. **Stuck-job recovery not implemented** — The `ix_fan_out_job_claimed` partial index and `findStuckJobs` repository method exist, but no poller logic resets stuck PROCESSING jobs. A crashed poller instance leaves jobs in PROCESSING indefinitely. Mitigation: the visibility timeout feature is documented in the blueprint's future extensions; implement before production deployment.
3. **No dead-letter replay mechanism** — Jobs in DEAD state are terminal. There's no admin API or scheduled task to replay them. Mitigation: DEAD jobs are logged with `last_error`; a manual SQL query can reset them to PENDING for replay if needed.
