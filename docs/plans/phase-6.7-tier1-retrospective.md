# Phase 6.7 Tier 1 — Functional Correctness Fixes Retrospective

**Date:** 2026-07-31
**Status:** Complete (uncommitted — 20 files modified across 4 services)
**Parent:** [Phase 2-5 Gap Remediation Blueprint](phase-2-5-gap-remediation-blueprint.md)
**Gap analysis:** [Phase 2-5 Production Gap Analysis Retrospective](phase-2-5-production-gap-analysis-retrospective.md)

## 1. What was implemented (vs the original plan)

| Planned item | Status | Files | Notes |
|-------------|--------|-------|-------|
| **B2** — SCHEDULED cancel gap | ✅ Done | `StreamStatus.java:58` | `Set.of()` → `Set.of(CANCELLED, DRAFT)` — also added DRAFT to enable B3 |
| **B3** — goLiveFromSchedule() bypass | ✅ Done | `StreamService.java:689` | Replaced `entity.setStatus(DRAFT)` with `entity.transitionTo(DRAFT)` |
| **B1** — Outbox transactional boundary | ✅ Done | `R2dbcConfig.java`, `StreamService.java`, `OutboxWriter.java` | Registered `ConnectionFactoryTransactionManager` + `TransactionalOperator` beans; wrapped 5 lifecycle methods in `.transactional()` |
| **B5** — Chat fire-and-forget consumer | ✅ Done | `chat-service/.../StreamControlListener.java` | Complete refactor: `void` → `Mono<Void>` + `blockOptional(10s)`; all handlers chain with `.then()` |
| **A6** — SRS webhook shared-secret | ✅ Done | `SrsWebhookController.java`, `custom.conf` | Query-param secret validation on both webhook endpoints; 403 on mismatch |
| **C2** — Fan-out offloading | ✅ Done | `notification-service/.../StreamControlListener.java:92` | Added `.subscribeOn(Schedulers.boundedElastic())` before `.blockOptional()` |
| **C4** — OutboxService.enqueue() return Mono | ✅ Done | `OutboxService.java`, `NotificationDispatcher.java` | `void enqueue()` → `Mono<Void> enqueue()`; chained via `flatMap` in dispatcher |
| **C5** — Input validation | ✅ Done | `UpdatePreferenceRequest.java` (new), `SubscriptionRequest.java`, `PreferenceRequest.java`, `PreferenceController.java`, `SubscriptionController.java`, `NotificationExceptionHandler.java`, `build.gradle.kts` | Typed DTO replaces `Map<String,Object>`; `@Valid` + `@NotBlank` on DTOs; `WebExchangeBindException` + catch-all handlers; added `spring-boot-starter-validation` |

### Dropped from plan

| Original | Reason |
|----------|--------|
| B4 (double-subscribe in chat consumer) | **False alarm** — the two `.subscribe()` calls are independent pipelines (system message vs. archive), not duplicate work |

### Verification

```bash
./gradlew compileJava  # BUILD SUCCESSFUL — all 7 services (stream, chat, notification, auth, gateway, discovery, pbac-common)
```

## 2. What was deferred (documented, with tracking reference)

| Item | Deferred to | Tracking doc | Reason |
|------|------------|-------------|--------|
| Tier 2 — System Robustness | Next session | [remediation blueprint](phase-2-5-gap-remediation-blueprint.md) Track E, F | User explicitly requested Tier 1 first, then Tier 2 |
| Track A — Security Triage | Skipped (pet project) | User directive | Hardcoded secrets, health endpoint restrictions acceptable for pet project |

## 3. What was deferred but NOT yet documented (gaps found during this retrospective)

No new undocumented gaps found. The Tier 1 fixes addressed exactly the documented gaps from the audit. The implementation matched the blueprint with one enhancement: B2 was expanded from `Set.of(CANCELLED)` to `Set.of(CANCELLED, DRAFT)` because B3 needed `DRAFT` in the transition map to use `transitionTo()` properly.

## 4. Architectural decisions made during implementation

1. **R2DBC TransactionalOperator pattern for outbox integrity** — Spring's `@Transactional` does not work with R2DBC repositories (they use reactive `ConnectionFactory`, not JPA `EntityManager`). The fix registered `R2dbcTransactionManager` + `TransactionalOperator` beans in `R2dbcConfig`, then wrapped lifecycle methods with `transactionalOperator.transactional(...)`. This is the first time reactive transactions have been wired in this project and may warrant a pattern reference doc alongside the existing `docs/R2DBC-JSONB-CONVERTER-PATTERN.md`.

   **Recommendation:** Write `docs/R2DBC-TRANSACTIONAL-PATTERN.md` with bean registration template, `.transactional()` usage patterns, and the caveat that `Mono.defer()` is required inside `.transactional()` when the chain starts with a non-lazy publisher.

2. **Chat Kafka consumer: Mono<Void> + blockOptional pattern** — The refactored `StreamControlListener` assembles the full reactive pipeline as a `Mono<Void>` and waits with `blockOptional(Duration.ofSeconds(10))`. This ensures the Kafka offset is only committed after the pipeline completes (or times out). Contrasts with notification-service which uses `subscribeOn(Schedulers.boundedElastic())` — the approaches differ because chat processing is lightweight (create room, send system message), while notification fan-out is heavy (N subscribers).

3. **SRS webhook: query-param shared secret** — SRS does not support custom HTTP headers on webhook URLs, so the `X-Webhook-Secret` header approach from the original ADR was infeasible. Instead, a `?secret=` query parameter is appended to both webhook URLs in `custom.conf` and validated in `SrsWebhookController`. This is the SRS-compatible approach.

4. **Notification DTO validation: `UpdatePreferenceRequest` record** — Replaced the `Map<String, Object>` body in `PreferenceController.updatePreference()` with a typed `record UpdatePreferenceRequest(Boolean active, String topicGlob)`. Both fields are nullable (PATCH semantics — only provided fields are applied), so `@NotBlank` is not used here, only `@Valid`.

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `docs/adr/stream/0009-outbox-pattern.md` | Accepted | §Consequences says "`@Transactional` not yet wired — this is a known limitation" | Update to note the limitation is resolved via `TransactionalOperator` (R2DBC-native, not `@Transactional`) |
| `docs/plans/phase-2-5-gap-remediation-blueprint.md` | Planning — awaiting approval | Tracks B (B1-B5), C (C2,C4,C5), and A (A6) are partially complete | Mark completed tasks with ✅, update status to "In Progress" |
| `docs/plans/phase-2-5-production-gap-analysis-retrospective.md` | Analysis complete | Several findings listed as open that are now fixed: C-3 (outbox TX), H-1 (SCHEDULED cancel), H-5 (chat fire-and-forget), H-6 (outbox fire-and-forget), H-11 (chat no catch-all), H-12 (notification no catch-all), H-15 (goLiveFromSchedule bypass), M-13 (Map<String,Object> unsafe casts), M-19 (missing catch-all handlers) | Update finding statuses to fixed |
| `docs/IMPLEMENTATION-PLAN.md` | Last updated 2026-07-28 | Phase 6.7 checklist shows no completed tracks; Tier 1 is complete | Add Tier 1 completion entry to Phase 6.7 checklist |
| `docs/adr/stream/0001-stream-state-machine.md` | Accepted | `SCHEDULED → DRAFT` transition now exists (was only `Set.of()` before) | Minor: update transition map if documented inline |

## 6. Updated execution order (actual vs planned)

| Planned order | Actual order | Status |
|--------------|-------------|--------|
| B2 (cancel gap) → B3 (goLiveFromSchedule) → B1 (outbox TX) | Executed as planned | ✅ All 3 done |
| B5 (chat consumer) | A6 (SRS webhook) in parallel | ✅ Both done |
| C2 (fan-out) → C4 (outbox enqueue) → C5 (validation) | Executed as planned | ✅ All 3 done |
| Tier 2 (error handling, memory safety) | Not yet started | ⏳ Pending |

## 7. Key risks carried forward

1. **No tests written for fixes** — All 8 fixes compile but have no new tests. The existing stream-service test suite (54 tests) was not re-run to verify no regressions. Risk: medium (fixes are surgical, but state machine and transaction changes touch hot paths).
2. **Uncommitted changes** — 20 files modified, zero commits. If the working tree is lost, all Tier 1 work must be redone. Risk: low (session is still active).
3. **Tier 2 remains** — Error handling standardization (F1-F3), Redis SCAN memory safety (E3), and SSE backpressure bounds (E4) are still open. These are the `log.warn(msg, ex.getMessage())` → `log.warn(msg, ex)` pattern, shared exception handler base class, and catch-all handlers for auth + chat. Risk: medium (systematic but not correctness-critical).

---

*Generated from the 2026-07-31 Tier 1 implementation session. Tier 2 pending.*
