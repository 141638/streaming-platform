# Phase 2–5 Production Readiness Gap Analysis — Retrospective

**Date:** 2026-07-28
**Status:** Analysis complete — 50+ gaps documented; remediation blueprint to follow
**Trigger:** `/consult` command auditing phases 2, 3, 4, 5 for production maturity
**Method:** 6 parallel subagents performed deep code audits across all 5 backend services + frontend + gateway

## 1. What was discovered (audit coverage)

| Audit track | Agent | Scope | Findings |
|-------------|-------|-------|----------|
| Phase 2 — Stream Lifecycle | code-explorer | stream-service, frontend channel page, outbox pattern, PBAC, SRS webhook | 12 findings (1 HIGH, 5 MEDIUM, 6 LOW) |
| Phase 3 — Real-time Chat | code-explorer | chat-service, Redis cache, PBAC dark-ship, Kafka consumer, frontend chat panel | 16 findings (1 HIGH, 5 MEDIUM, 3 LOW, 7 NOTE) |
| Phase 4 — Viewer Experience | code-explorer | stream-service, frontend watch/browse pages, SRS integration, SSE, presence/heartbeat | 29 findings (4 CRITICAL, 12 HIGH, 8 MEDIUM, 5 LOW) |
| Phase 5 — Notifications | code-explorer | notification-service, SSE delivery, fan-out, email adapter, subscription idempotency, frontend bell/settings | 10 findings (2 CRITICAL, 2 HIGH, 5 MEDIUM, 1 INFO) |
| Silent Failure Hunt | silent-failure-hunter | All 5 backend services + gateway + pbac-common | 32 findings (3 CRITICAL, 9 HIGH, 13 MEDIUM, 7 LOW) |
| Security Audit | security-reviewer | All 5 backend services + gateway + Docker configs + env files | 20 findings (2 CRITICAL, 7 HIGH, 9 MEDIUM, 2 LOW) |

**Total: 50+ unique findings** (after deduplication across audit tracks).

## 2. Findings by severity (deduplicated)

### CRITICAL (must fix before any deployment)

| # | Service | Finding | Source |
|---|---------|---------|--------|
| C-1 | All 5 services | Hardcoded JWT HMAC secret `MNH6CwQ7H4xAf69hpn0sc2Rn+wxT/d+I9QWELikQqgM=` as YAML default — anyone with repo access can forge valid JWTs | Security |
| C-2 | auth-service | Hardcoded Gmail app password `vzlc wapx wxrt onfn` committed to source | Security |
| C-3 | stream-service | Outbox pattern has no `@Transactional` — entity save and outbox write are separate auto-commit transactions. Crash between them = permanently lost event | Phase 2 |
| C-4 | notification-service | Zero test coverage — no test files exist for any class | Phase 5 |
| C-5 | notification-service | Fan-out blocks Kafka listener thread. ADR-0002 promised `subscribeOn(Schedulers.boundedElastic())` — never implemented. Unpaginated query loads all followers into memory, blocks consumer ≤10s | Phase 5 |
| C-6 | notification-service | `EmailAdapter.send()` is a complete no-op — silently marks outbox entries SENT without sending email. The outbox drains with no actual delivery | Phase 5 |
| C-7 | auth-service | No catch-all `@ExceptionHandler(Exception.class)` — unexpected errors leak full stack traces to clients | Silent-failures |
| C-8 | chat-service | `StreamControlListener` double-subscribes the archive chain — two `.subscribe()` calls trigger duplicate archives and duplicate outbox events | Silent-failures |

### HIGH (should fix before production)

| # | Service | Finding | Source |
|---|---------|---------|--------|
| H-1 | stream-service | `SCHEDULED` streams can't be cancelled — `StreamStatus.SCHEDULED.allowedTransitions()` returns empty set. User gets `IllegalStateException` → HTTP 500 | Phase 2 |
| H-2 | stream-service | Full SCAN `collectList()` in `HeartbeatHarvestService` and `ViewerCountPushService` — collects ALL Redis presence keys into one in-memory `List`. 5K viewers = OOM risk | Phase 4 |
| H-3 | stream-service | Hardcoded SRS webhook IP `192.168.30.27` in `custom.conf` — won't work on any other machine | Phase 4 |
| H-4 | chat-service | `getMessagesBefore` backfill writes to ZSET without trimming — only writes trigger `trimToRetention`. Repeated scroll-up bloats cache unboundedly | Phase 3 |
| H-5 | chat-service | Fire-and-forget Kafka consumer — every event handler uses `.subscribe()` without awaiting. Kafka offset committed before pipeline completes. Crash = permanent event loss | Phase 3 |
| H-6 | notification-service | `OutboxService.enqueue()` fire-and-forget `.subscribe()` — failed outbox writes are silently lost with no retry | Phase 5 |
| H-7 | All 6 services | Health endpoints expose full system details publicly — `show-details: always` + `permitAll()` on `/actuator/**` | Security |
| H-8 | chat-service | PBAC disabled in committed dev env file — `chat.env: CHAT_PBAC_ENABLED=false`. Deployment sourcing this file runs without authorization | Security |
| H-9 | auth-service | No login failure logging — brute-force attacks and credential stuffing are completely invisible | Silent-failures |
| H-10 | auth-service | Password reset email failure is silent — user told "email sent" when it wasn't. Valid token exists with no delivery path | Silent-failures |
| H-11 | chat-service | `ChatExceptionHandler` has no catch-all — unexpected exceptions return non-`ChatApiError` body, potentially leaking internals | Silent-failures |
| H-12 | notification-service | `NotificationExceptionHandler` has no catch-all — same leak risk | Silent-failures |
| H-13 | stream-service | Backpressure buffer unbounded — `onBackpressureBuffer(64)` with default `OverflowStrategy.BUFFER` grows unboundedly under slow clients | Phase 4 |
| H-14 | stream-service | No stale SSE connection cleanup — zombie sinks accumulate on WiFi drop/laptop sleep | Phase 4 |
| H-15 | stream-service | `goLiveFromSchedule()` bypasses `transitionTo()` via raw `setStatus(DRAFT)` — bypasses timestamp management and invariant checks | Phase 2 |
| H-16 | auth-service | `GlobalExceptionHandler` has zero logging — security-relevant events (invalid tokens, validation failures) have no audit trail | Silent-failures |

### MEDIUM (fix when convenient)

| # | Service | Finding | Source |
|---|---------|---------|--------|
| M-1 | stream-service | O(N) SCAN for viewer counts — scans ALL presence keys instead of filtering by active stream at Redis level | Phase 4 |
| M-2 | stream-service | Cursor pagination uses millisecond timestamps — same-ms streams cause duplicates/skips | Phase 4 |
| M-3 | stream-service | Browse page category filtering client-side only — category invisible if no stream in first 24 results | Phase 4 |
| M-4 | stream-service | Safari native HLS path has zero error handling — blank screen on failure | Phase 4 |
| M-5 | stream-service | SSE route ordering in gateway is fragile — moving SSE route below generic route kills connections silently | Phase 4 |
| M-6 | stream-service | `deleteStream()` has no `OptimisticLockingFailureException` mapping — yields 500 instead of 409 | Phase 2 |
| M-7 | stream-service | `StreamExceptionHandler` logs nothing — all 7 specific handlers return error responses with zero log output | Silent-failures |
| M-8 | chat-service | `STREAM_ENDED` before room exists is a silent no-op; subsequent `STREAM_CREATED` creates ACTIVE room for ended stream | Phase 3 |
| M-9 | chat-service | DLQ configured in `KafkaConsumerConfig` but effectively unused — all errors caught internally before DLQ can act | Phase 3 |
| M-10 | chat-service | No handler for validation errors (`WebExchangeBindException`) — response shape differs from `ChatApiError` envelope | Phase 3 |
| M-11 | chat-service | `ChatExceptionHandler` logs everything at DEBUG — authorization denials and ban enforcements invisible in production | Silent-failures |
| M-12 | notification-service | `NotificationSettingsPage` has no error state — API failures indistinguishable from "no data" | Phase 5 |
| M-13 | notification-service | `PreferenceController` accepts raw `Map<String,Object>` body with unsafe casts — bypasses all input validation | Phase 5 |
| M-14 | notification-service | `NotificationExceptionHandler` logs everything at DEBUG — subscription errors invisible in production | Silent-failures |
| M-15 | notification-service | Follower notification body lacks stream title — "Stream started broadcasting." vs broadcaster's richer event | Phase 5 |
| M-16 | auth-service | `confirmReset()` revokes Redis tokens BEFORE DB save — if DB fails, user is locked out with revoked tokens | Silent-failures |
| M-17 | All services | ~15 locations log `ex.getMessage()` without passing `ex` — stack traces lost | Silent-failures |
| M-18 | All controllers | No `@PreAuthorize` or method-level security annotations — authorization is invisible at the controller layer | Security |
| M-19 | Cross-cutting | 3 of 4 services missing catch-all `@ExceptionHandler(Exception.class)` | Silent-failures |

### Systematic gaps (not individual findings but patterns)

| # | Category | Description |
|---|----------|-------------|
| S-1 | Test execution | Tests exist (chat: 13 files, stream: 2, frontend: several) but are **never executed** — Docker/Chrome-gated per project policy. notification-service has zero tests. |
| S-2 | Observability | No Micrometer metrics, no Prometheus endpoint, no Grafana dashboards, no trace propagation (designed in `TRACE-PROPAGATION.md` but not wired) |
| S-3 | Infrastructure maturity | Kafka: auto-create topics ON, no schema registry, partition counts unspecified, consumer auto-commit. Redis: single-instance, no Lua scripting, no Sentinel/Cluster. |
| S-4 | Prod defaults | JWT secret, DB passwords, Redis passwords, SMTP credentials all have hardcoded YAML defaults — services start with dev credentials in production if env vars not set |
| S-5 | Email delivery | `EmailAdapter` is a skeleton — `log.debug("Email adapter skeleton: email sending deferred")`. Outbox poller drains entries as SENT with no actual delivery. No one would know. |
| S-6 | Outbox integrity | `@Transactional` never wired. Entity save + outbox write = 2 auto-commit transactions. Events can be lost on crash. |
| S-7 | Fan-out scaling | Inline fan-out blocks Kafka listener thread, loads all followers into memory unbounded, no batching beyond flatMap concurrency=8. 100K followers = 100K individual INSERTs. |
| S-8 | SSE robustness | Unbounded backpressure buffers, no stale connection cleanup, two different reconnection strategies, missing error/connected signals on some services |

## 3. What was deferred but NOT previously documented

These gaps existed at the time phases 2-5 were marked "✅ Done" but were never tracked:

| Item | Phase | Context | Recommended action |
|------|-------|---------|-------------------|
| Outbox transactional boundary | 2/6 | `@Transactional` never applied — entity save and outbox write in separate auto-commit transactions. Javadoc says "MUST invoke within @Transactional" but no TX boundary exists | Wire `@Transactional` on StreamService lifecycle methods; add integration test verifying TX rollback |
| SCHEDULED stream cancel gap | 2 | Empty `allowedTransitions()` makes SCHEDULED streams uncancellable | Add `SCHEDULED → CANCELLED` to allowed transitions |
| PBAC dark-ship stale doc | 3 | Retro said `chat.pbac.enabled=false` but 6.2a changed default to `true`. ADR-0000 still references unscoped dedup key | Update ADR-0000 dedup key description |
| EmailAdapter skeleton | 5 | Retro says "Email adapter skeleton exists" but doesn't convey that it silently marks entries SENT with no delivery | Either implement or remove the outbox poller for email; at minimum log ERROR when entries are silently consumed |
| Fan-out blocking thread | 5 | ADR-0002 §4 promises `subscribeOn(Schedulers.boundedElastic())` but code has `blockOptional()` with no offloading | Implement offloading or accept risk with metrics |
| Fire-and-forget Kafka consumer | 3 | Every event handler in chat-service `StreamControlListener` uses `.subscribe()` without awaiting — offsets committed before work completes | Convert to reactive pipeline that returns `Mono<Void>` and commit offset after completion |
| Zero notification-service tests | 5 | No test files exist. `build.gradle.kts` has test dependencies but no test classes | Write minimum smoke tests for NotificationDispatcher, SseConnectionRegistry, StreamControlListener |

## 4. Architectural decisions captured during this audit

1. **Outbox pattern is incomplete without `@Transactional`** — The outbox guarantee ("same database transaction") is documented in ADR-0009 but never enforced in code. This is a **correctness gap** in an ADR that is marked Accepted. Either the ADR should be revised to document the actual at-most-once behavior, or `@Transactional` should be wired. Recommendation: wire the TX boundary and keep the ADR as-is.

2. **Chat PBAC now defaults to ON** — The dark-ship period (`chat.pbac.enabled=false`) ended with Phase 6.2a. The default is now `CHAT_PBAC_ENABLED:true` with a production hard-fail in `ChatAuthorization.enforceOrWarn()`. This is correct but the ADRs and retros still reference the old default. Recommendation: update stale docs.

3. **Two-tier test policy creates a blind spot** — Tests are written but gated on Docker/Chrome availability. This means no one has ever verified the cache-aside integration tests, Kafka tests, or frontend tests actually pass. The policy is pragmatic but the gap is unmonitored. Recommendation: add a CI job that runs the full test suite with Docker, even if local execution remains optional.

4. **Error visibility is a systematic weakness** — Across all services, exception handlers log at DEBUG or not at all, `.getMessage()` is preferred over passing the exception, and catch-all handlers are missing in 3 of 4 services. This isn't a single bug — it's a consistency gap in how the platform handles failures. Recommendation: create a shared error-handling convention in pbac-common (consistent `@RestControllerAdvice` base class, `ProblemDetail`-style envelope, mandatory logging at WARN for security events).

## 5. Documents to update (stale vs current state)

| Document | Current status | What's stale | Action |
|----------|---------------|-------------|--------|
| `IMPLEMENTATION-PLAN.md` | Last updated 2026-07-28 | Phase 2-5 marked "✅ Done" but significant gaps exist; Phase 6 checklist is aspirational | Add gap tracking to each phase; update readiness assessment |
| `docs/adr/stream/0009-outbox-pattern.md` | Accepted | Documents "same DB transaction" guarantee that is not enforced in code | Either add implementation note about missing `@Transactional` or fix the code |
| `docs/adr/notification/0000-architecture-foundation.md` | Accepted | References unscoped dedup key `dedup:stream-event:{eventId}` — was changed to scoped in 5.2b | Update dedup key format to match code |
| `docs/adr/notification/0002-notification-delivery-architecture.md` | Accepted | §4 says fan-out is offloaded via `subscribeOn(Schedulers.boundedElastic())` — code does `.blockOptional()` with no offloading | Update to match implementation or fix code |
| `docs/REDIS-KAFKA-PRODUCTION-GAP.md` | "living — updated as gaps are closed" | Several listed gaps are now done (K1, K2, K3, K10) but still marked open; new gaps found (R10-R15, K13-K15) | Mark closed gaps as done; add new gaps from this audit |
| `docs/plans/authorization-gap-remediation-retrospective.md` | Complete | Claims 7 gaps closed — but C-2 (chat PBAC in dev env) partially undermines this | Add note about env file risk |

## 6. Key risks carried forward

1. **Outbox integrity** — Events can be silently lost on crash between entity save and outbox write. No one would know. Mitigation: fix `@Transactional` boundary or add reconciliation job.
2. **Email delivery illusion** — Outbox poller marks entries SENT with no actual delivery. If someone deploys thinking emails work, they don't. Mitigation: either implement or hard-fail the outbox poller.
3. **Fan-out at scale** — First streamer with 10K+ followers will trigger a Kafka consumer rebalance when fan-out exceeds `max.poll.interval.ms`. Mitigation: implement offloading before scale.
4. **SSE OOM** — Unbounded backpressure buffers + no stale connection cleanup = memory leak under sustained load. Mitigation: add buffer bounds + periodic zombie drain.
5. **Hardcoded secrets** — JWT HMAC secret, DB passwords, and Gmail app password in source. Any environment deployed without overriding env vars is trivially compromised. Mitigation: remove defaults, fail at startup.
6. **Silent failures everywhere** — `onErrorComplete()` in 7 scheduled services, DEBUG-only logging in exception handlers, `ex.getMessage()` without stack traces. Operational blindness. Mitigation: systematic error-handling cleanup across all services.

---

## 7. Remediation approach

A detailed remediation blueprint will be written as `docs/plans/phase-2-5-gap-remediation-blueprint.md` with:

- **Track A — Security Triage** (1 day): Remove hardcoded secrets, add catch-all exception handlers, restrict health endpoints
- **Track B — Outbox Integrity** (1 day): Wire `@Transactional`, fix SCHEDULED stream cancel, fix double-subscribe in chat consumer
- **Track C — Notification Hardening** (2-3 days): Add tests, fix fan-out blocking, implement real email or remove skeleton
- **Track D — Observability Foundation** (2-3 days): Micrometer metrics, Prometheus endpoint, trace propagation wiring
- **Track E — Infrastructure Maturity** (1-2 days): Lua scripting, auto-create-topics off, connection pool config, SCAN limits
- **Track F — Error Handling Standardization** (1 day): Catch-all handlers, WARN logging for security events, exception-passing convention
- **Track G — Test Execution** (ongoing): CI pipeline with Docker, gating on test pass

---

*Generated from the 2026-07-28 6-agent production readiness audit.
Remediation blueprint follows.*
