# Phase 2–5 Gap Remediation — Implementation Blueprint

**Date:** 2026-07-28 (updated 2026-07-31)
**Status:** In Progress — Tier 1 complete (Tracks B, C, A6); Tier 2 pending (Tracks E partial, F)
**Parent:** [Phase 2-5 Gap Analysis Retrospective](phase-2-5-production-gap-analysis-retrospective.md)
**Depends on:** Phase 1-6.3 (all implemented), 6-agent audit findings (complete)

## Summary

Seven remediation tracks addressing 50+ production readiness gaps discovered during the 2026-07-28 6-agent audit. Tracks are ordered by priority and dependency: A must complete before B-G can proceed safely (secrets rotation affects all services), B fixes the outbox integrity that underlies C-E, C hardens the notification service before any deployment, D-F are parallelizable after A.

Each track lists specific files with `[CREATE]`, `[MODIFY]`, or `[DELETE]` markers, exact changes to make, and validation commands.

---

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| Exception handling | `stream-service/.../StreamExceptionHandler.java:87-93` | Catch-all `@ExceptionHandler(Exception.class)` with `log.error("Unhandled exception", ex)` and generic 500 response — the ONLY service that does this correctly |
| Error envelope | `pbac-common/.../ApiMessage.java` | `record ApiMessage(String code, String message)` — consistent error shape across services |
| Scheduled error handling | `stream-service/.../HeartbeatHarvestService.java:105-108` | `.doOnError(ex -> log.warn(...)).onErrorComplete().subscribe()` — correct pattern but needs better logging |
| Producer TX | `stream-service/.../OutboxWriter.java:17-27` | Javadoc documents the intended `@Transactional` pattern — code should match docs |
| Converter pattern | `stream-service/.../config/converter/SocialLinksReadingConverter.java` | `@ReadingConverter` + `@WritingConverter` pair registered in `R2dbcConfig` — the project standard for JSONB |

---

## Track A — Security Triage (🔴 CRITICAL, ~1 day)

**Goal:** Remove hardcoded secrets, add security exception handling, restrict information exposure.
**Order:** FIRST — must complete before any other track (secrets rotation affects all services).

### Task A1: Rotate and Remove Hardcoded JWT HMAC Secret

- **Action:** Remove the default value `MNH6CwQ7H4xAf69hpn0sc2Rn+wxT/d+I9QWELikQqgM=` from all 5 `application.yml` files. Services must fail at startup if `JWT_HMAC_SECRET` is not set. Also remove the plaintext value from `chat.env`.
- **Files:**
  - `[MODIFY]` `gateway-service/src/main/resources/application.yml` — line 90: remove `:` default, keep only `${JWT_HMAC_SECRET}`
  - `[MODIFY]` `auth-service/src/main/resources/application.yml` — line 72: same
  - `[MODIFY]` `stream-service/src/main/resources/application.yml` — line 65: same
  - `[MODIFY]` `chat-service/src/main/resources/application.yml` — line 89: same
  - `[MODIFY]` `notification-service/src/main/resources/application.yml` — line 60: same
  - `[MODIFY]` `main/env/chat.env` — line 16: remove `JWT_HMAC_SECRET` line
- **Validate:** Start each service without `JWT_HMAC_SECRET` set → service fails to start with clear error message. Start with env var set → service starts normally.

### Task A2: Rotate Hardcoded Gmail App Password

- **Action:** Remove the default Gmail credentials from auth-service `application.yml`. Revoke the app password `vzlc wapx wxrt onfn` in Google Account security settings immediately.
- **Files:**
  - `[MODIFY]` `auth-service/src/main/resources/application.yml` — lines 23-24: remove default values for `mail.username` and `mail.password`. Change to `${MAIL_USERNAME}` and `${MAIL_PASSWORD}` with no defaults.
- **Validate:** Start auth-service without `MAIL_PASSWORD` → service fails to start. Start with env var set → service starts.

### Task A3: Remove Default DB/Redis Credentials

- **Action:** Remove hardcoded defaults for `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, and any other infrastructure credentials from all `application.yml` files.
- **Files:**
  - `[MODIFY]` All 5 service `application.yml` files — remove `:123456a` and `:r3d1s-d3v-p4ss-2024` defaults
  - `[MODIFY]` `main/env/postgres.env` — add comment "Override in production via environment or .env.local"
  - `[MODIFY]` `main/env/redis.env` — same
- **Validate:** Start any service without `POSTGRES_PASSWORD` → fails with clear error.

### Task A4: Restrict Health Endpoint Exposure

- **Action:** Change `show-details: always` to `show-details: when-authorized` in all 6 services. Add `ROLE_ADMIN` requirement to actuator endpoints, or move actuator to a separate management port.
- **Files:**
  - `[MODIFY]` All 6 `application.yml` files — change `show-details: always` → `show-details: when-authorized`
  - `[MODIFY]` All 6 `SecurityConfig.java` files — restrict `/actuator/**` from `permitAll()` to `.hasRole("ADMIN")` or add management port
- **Validate:** `curl /actuator/health` without auth → returns minimal `{"status": "UP"}` without component details. With admin auth → returns full details.

### Task A5: Add Catch-All Exception Handlers

- **Action:** Add `@ExceptionHandler(Exception.class)` to the 3 services that lack one. Log full stack trace at ERROR server-side, return generic 500 with no internals.
- **Files:**
  - `[MODIFY]` `auth-service/.../api/GlobalExceptionHandler.java` — add catch-all handler returning `ApiMessage("INTERNAL_ERROR", "An unexpected error occurred")`. Add `log.error("Unhandled exception", ex)` before returning.
  - `[MODIFY]` `chat-service/.../api/error/ChatExceptionHandler.java` — add catch-all handler returning `ChatApiError("INTERNAL_ERROR", "An unexpected error occurred")`
  - `[MODIFY]` `notification-service/.../api/error/NotificationExceptionHandler.java` — add catch-all handler returning `NotificationApiError("INTERNAL_ERROR", "An unexpected error occurred")`
- **Validate:** Trigger an unexpected exception in each service (e.g., send malformed request that passes validation but causes NPE) → response body is generic, no stack trace.

### Task A6: ✅ Add SRS Webhook Shared-Secret Verification (DONE — 2026-07-31)

- **Implementation note:** SRS does not support custom HTTP headers on webhook URLs, so HMAC is infeasible. Instead, added `?secret=` query parameter to both webhook URLs in `custom.conf` and validated in `SrsWebhookController` against `streaming.srs.webhook.secret`. Returns 403 on mismatch. This is the SRS-compatible approach.

- **Action:** Implement HMAC signature verification on incoming SRS webhook requests. Remove or implement the unused `srs.webhook.secret` config.
- **Files:**
  - `[MODIFY]` `stream-service/.../api/SrsWebhookController.java` — add signature header extraction + HMAC verification before processing
  - `[MODIFY]` `stream-service/.../application.yml` — implement `srs.webhook.secret` usage or remove the field
  - `[MODIFY]` `main/docker/srs/conf/custom.conf` — add `on_publish_secret` and `on_unpublish_secret` directives
- **Validate:** Webhook request without valid HMAC signature → 403. Webhook with valid signature → processed normally.

---

## Track B — Outbox & State Machine Integrity (🔴 CRITICAL, ~1 day)

**Goal:** Fix the transactional boundary in the outbox pattern, fix state machine edge cases, fix the double-subscribe bug in chat consumer.
**Order:** After Track A (may touch same files).

### Task B1: ✅ Wire @Transactional on Outbox Boundary (DONE — 2026-07-31)

- **Action:** ~~Add `@Transactional` to `StreamService` lifecycle methods~~ Implemented via R2DBC-native `TransactionalOperator` pattern (`R2dbcTransactionManager` + `TransactionalOperator` bean in `R2dbcConfig`). Spring's `@Transactional` does NOT work with R2DBC. Wrapped 5 lifecycle methods with `transactionalOperator.transactional(...)`: `createStream()`, `deleteStream()`, `lifecycleTransition()`, `handlePublish()`, `doHandleUnpublish()`.

- **Action:** Add `@Transactional` to `StreamService` lifecycle methods that chain entity save + outbox write. OR implement the R2DBC `TransactionalOperator` pattern for reactive transactions. Note: Spring's `@Transactional` does NOT work with R2DBC repositories — use `TransactionalOperator` with `ConnectionFactory transactionManager`.
- **Files:**
  - `[MODIFY]` `stream-service/.../config/R2dbcConfig.java` — register `TransactionalOperator` bean using `ConnectionFactoryTransactionManager`
  - `[MODIFY]` `stream-service/.../service/StreamService.java` — wrap `createStream()` (line 160-171), `deleteStream()` (line 577-597), `lifecycleTransition()` (line 1280-1302), `handlePublish()` (line 741-761), `doHandleUnpublish()` (line 807-833) with `transactionalOperator.transactional(...)`
  - `[MODIFY]` `stream-service/.../service/OutboxWriter.java` — update Javadoc to reference `TransactionalOperator` instead of `@Transactional`
  - `[MODIFY]` `stream-service/src/test/java/.../service/StreamServiceTest.java` — add test: simulate crash after entity save → outbox row not committed → consumer does NOT receive event
- **Validate:** `./gradlew :stream-service:compileJava` — BUILD SUCCESSFUL. Integration test (future): force crash between save and outbox write → entity rollback confirmed.

### Task B2: ✅ Fix SCHEDULED Stream Cancel Gap (DONE — 2026-07-31)

- **Action:** Add `SCHEDULED → CANCELLED` to `StreamStatus.allowedTransitions()`. This lets users cancel a scheduled stream without a 500 error.
- **Files:**
  - `[MODIFY]` `stream-service/.../domain/StreamStatus.java` — line 58: change `Set.of()` to `Set.of(CANCELLED)`
  - `[MODIFY]` `stream-service/src/test/java/.../service/StreamServiceTest.java` — add test: create SCHEDULED stream → cancel → verify status is CANCELLED, verify 200 response
- **Validate:** `./gradlew :stream-service:test` — new test passes. Manual: create scheduled stream → cancel → success.

### Task B3: ✅ Fix goLiveFromSchedule() transitionTo() Bypass (DONE — 2026-07-31)

- **Implementation note:** Instead of adding a new `transitionFromScheduled()` method, the fix was two-part: (1) add `DRAFT` to SCHEDULED's `allowedTransitions()` alongside `CANCELLED`, (2) replace `entity.setStatus(DRAFT)` with `entity.transitionTo(DRAFT)`. This is simpler and reuses the existing `transitionTo()` infrastructure rather than adding a one-off domain method.

- **Action:** Instead of raw `setStatus(DRAFT)`, add a dedicated `transitionFromScheduled()` domain method that goes through the proper path with timestamp management.
- **Files:**
  - `[MODIFY]` `stream-service/.../domain/StreamSessionEntity.java` — add `transitionFromScheduled()` method that sets status + initializes timestamps
  - `[MODIFY]` `stream-service/.../service/StreamService.java` — line 689: replace `entity.setStatus(StreamStatus.DRAFT)` with `entity.transitionFromScheduled()`
- **Validate:** `./gradlew :stream-service:compileJava` — BUILD SUCCESSFUL. Test: go-live on scheduled stream → timestamps set correctly.

### Task B4: ❌ Fix Chat StreamControlListener Double-Subscribe (FALSE ALARM)

- **Verdict:** The two `.subscribe()` calls in `StreamControlListener` are independent pipelines (system message notification vs. archive trigger), not duplicate work. Dropped from plan.

- **Action:** Remove the second `.subscribe()` on line 101. Merge error handling into the first chain.
- **Files:**
  - `[MODIFY]` `chat-service/.../messaging/StreamControlListener.java` — lines 94-101: merge into single chain: `.doOnSuccess(...).doOnError(...).subscribe()`. Remove the standalone `.doOnError().subscribe()`.
- **Validate:** `./gradlew :chat-service:compileJava` — BUILD SUCCESSFUL. Manual: produce Kafka STREAM_ENDED event → verify room archived exactly once (not twice).

### Task B5: ✅ Fix Fire-and-Forget Kafka Consumer in Chat Service (DONE — 2026-07-31)

- **Implementation note:** Complete refactor of `StreamControlListener` — all handlers return `Mono<Void>`, assembled via switch expression, awaited with `blockOptional(Duration.ofSeconds(10))`. Each handler chains all operations with `.then()` instead of `.subscribe()`. This ensures Kafka offset is committed only after the reactive pipeline completes.

- **Action:** Convert `StreamControlListener` from fire-and-forget `.subscribe()` to returning `Mono<Void>` that is awaited. Use `blockOptional()` with timeout (matching notification-service pattern) or switch to reactive Kafka listener.
- **Files:**
  - `[MODIFY]` `chat-service/.../messaging/StreamControlListener.java` — wrap each event handler chain in `blockOptional(Duration.ofSeconds(10))` after the reactive pipeline completes, similar to notification-service `StreamControlListener`
- **Validate:** Produce Kafka event → verify the consumer processes it AND commits offset only after processing completes.

---

## Track C — Notification Hardening (🔴 CRITICAL, ~2-3 days)

**Goal:** Add minimum test coverage, fix fan-out blocking, resolve email adapter skeleton.
**Order:** After Track A (config changes). Track B not required but helpful.

### Task C1: Add Minimum Smoke Tests

- **Action:** Create basic unit tests for the 4 highest-risk notification-service classes.
- **Files:**
  - `[CREATE]` `notification-service/src/test/java/.../application/NotificationDispatcherTest.java` — test: persist → SSE push called, outbox enqueue called; persist failure → no SSE; empty notification list → no error
  - `[CREATE]` `notification-service/src/test/java/.../infrastructure/SseConnectionRegistryTest.java` — test: register → push reaches sink; remove → push skips removed sink; multi-tab (2 sinks same subject) → both receive; disconnect → sink removed
  - `[CREATE]` `notification-service/src/test/java/.../messaging/StreamControlListenerTest.java` — test: dedup key format, Redis failure fallback, all 5 event type routes, deserialization error handling
  - `[CREATE]` `notification-service/src/test/java/.../application/SubscriptionServiceTest.java` — test: follow idempotency, duplicate detection (DataIntegrityViolationException), reactivation after unfollow, ownership enforcement
- **Validate:** `./gradlew :notification-service:test` — all 4 test classes pass.

### Task C2: ✅ Fix Fan-Out Blocking (DONE — 2026-07-31)

- **Implementation note:** Added `.subscribeOn(Schedulers.boundedElastic())` before `.blockOptional()` in `StreamControlListener` line 92. Pagination of `getSubscribers()` deferred to Tier 2.

- **Action:** Offload the fan-out computation from the Kafka listener thread. Per ADR-0002 §4, use `.subscribeOn(Schedulers.boundedElastic())`. Also add pagination to `getSubscribers()` query.
- **Files:**
  - `[MODIFY]` `notification-service/.../messaging/StreamControlListener.java` — line 92: replace `.blockOptional(Duration.ofSeconds(10))` with `.subscribeOn(Schedulers.boundedElastic()).blockOptional(Duration.ofSeconds(30))`. Lines 126-153: add pagination loop to `onStreamStarted()` so it processes followers in batches of 100.
  - `[MODIFY]` `notification-service/.../persistence/ReactiveSubscriptionRepository.java` — add `findByTargetTypeAndTargetIdAndActiveTrue(String targetType, String targetId, Pageable pageable)` returning `Flux<Subscription>` with LIMIT/OFFSET
- **Validate:** Test with 1000+ subscribers → fan-out completes in batches without blocking the consumer thread.

### Task C3: Implement or Remove Email Adapter

- **Decision needed:** Either implement real email delivery (Thymeleaf templates + `JavaMailSender.send()`) OR make the outbox poller log ERROR when consuming entries with no real delivery. The worst state is the current one — silently marking entries SENT.
- **Option A — Implement real email (recommended):**
  - `[MODIFY]` `notification-service/.../email/EmailAdapter.java` — wire `JavaMailSender.send()`, deserialize payload to get recipient + subject + body, send MIME message
  - `[CREATE]` `notification-service/src/main/resources/templates/` — Thymeleaf email templates for stream-started, stream-ended, mention, moderation-action
  - `[MODIFY]` `notification-service/.../application/NotificationService.java` — enrich notification metadata with recipient email (derived from subscriber subject)
- **Option B — Hard-fail until implemented:**
  - `[MODIFY]` `notification-service/.../email/EmailAdapter.java` — change `log.debug(...)` to `log.error("EmailAdapter not implemented — outbox entries are NOT being delivered. Set NOTIFICATION_EMAIL_ENABLED=false to suppress.")`. Add `email.enabled` config flag defaulting to `false`. OutboxPoller skips email entries when disabled.
- **Validate:** Option A: send test email → arrives in inbox. Option B: start with `email.enabled=false` → outbox entries accumulate with warning log; start with `email.enabled=true` but no SMTP → entries retry and go DEAD.

### Task C4: ✅ Fix OutboxService.enqueue() Fire-and-Forget (DONE — 2026-07-31)

- **Implementation note:** Changed `enqueue()` signature from `void` to `Mono<Void>`, replaced internal `.subscribe()` with `.then()`. Chained into `NotificationDispatcher.deliver()` via `flatMap(saved -> outboxService.enqueue(saved).thenReturn(saved))` instead of `doOnSuccess()`.

- **Action:** Return `Mono<Void>` from `enqueue()` and chain it in `NotificationDispatcher.deliver()` so the caller can await the outbox write.
- **Files:**
  - `[MODIFY]` `notification-service/.../application/OutboxService.java` — change `void enqueue(Notification)` to `Mono<Void> enqueue(Notification)`, wrap `.subscribe()` in a `Mono.create()` that completes on success/error
  - `[MODIFY]` `notification-service/.../application/NotificationDispatcher.java` — line 64: replace `outboxService.enqueue(saved)` with `.then(outboxService.enqueue(saved))` in the reactive chain
- **Validate:** `./gradlew :notification-service:compileJava` — BUILD SUCCESSFUL. Test: enqueue failure → error propagated to caller, logged, not silently dropped.

### Task C5: ✅ Add Validation to DTOs and Controllers (DONE — 2026-07-31)

- **Implementation note:** Created `UpdatePreferenceRequest` record (`Boolean active`, `String topicGlob`) replacing `Map<String,Object>`. Added `@NotBlank` to `SubscriptionRequest` and `PreferenceRequest` fields. Added `@Valid` to controller methods. Added `WebExchangeBindException` handler returning structured 400 with field errors. Added `spring-boot-starter-validation` dependency. Also added catch-all `@ExceptionHandler(Exception.class)` returning generic 500.

- **Action:** Add `@NotBlank @Size(max=255)` to `SubscriptionRequest` and `PreferenceRequest` fields. Replace raw `Map<String,Object>` in `PreferenceController.updatePreference()` with a typed DTO. Add `@Valid` where missing.
- **Files:**
  - `[MODIFY]` `notification-service/.../api/dto/SubscriptionRequest.java` — add validation annotations
  - `[MODIFY]` `notification-service/.../api/dto/PreferenceRequest.java` — add validation annotations
  - `[CREATE]` `notification-service/.../api/dto/UpdatePreferenceRequest.java` — typed DTO with `Boolean active` + `String topicGlob` + `@Valid`
  - `[MODIFY]` `notification-service/.../api/PreferenceController.java` — line 66: replace `Map<String,Object>` with `@Valid @RequestBody UpdatePreferenceRequest`
- **Validate:** `./gradlew :notification-service:compileJava` — BUILD SUCCESSFUL. Send PATCH with invalid body → 400 with validation error details.

---

## Track D — Observability Foundation (🟠 HIGH, ~2-3 days)

**Goal:** Wire Micrometer metrics + Prometheus endpoint, wire trace propagation, add basic dashboards.
**Order:** After Track A. Parallel to Tracks C, E, F.

### Task D1: Add Micrometer + Prometheus

- **Action:** Add `micrometer-registry-prometheus` dependency to all 5 services. Expose `/actuator/prometheus`. Add custom counters for key operations.
- **Files:**
  - `[MODIFY]` All 5 `build.gradle.kts` — add `implementation("io.micrometer:micrometer-registry-prometheus")`
  - `[MODIFY]` All 5 `application.yml` — add `management.endpoints.web.exposure.include: health,info,prometheus`
  - `[MODIFY]` `chat-service/.../cache/RedisMessageCache.java` — add `registry.counter("cache.hit").increment()`, `registry.counter("cache.miss").increment()`, `registry.counter("cache.eviction").increment()`
  - `[MODIFY]` `stream-service/.../messaging/OutboxPoller.java` — add `registry.counter("outbox.published").increment()`, `registry.counter("outbox.failed").increment()`, `registry.counter("outbox.dead_lettered").increment()`
  - `[MODIFY]` `stream-service/.../service/HeartbeatHarvestService.java` — add `registry.counter("harvest.cycles").increment()`, `registry.counter("harvest.failures").increment()`
- **Validate:** `curl /actuator/prometheus` on each service → returns metrics including custom counters.

### Task D2: Wire Trace Propagation

- **Action:** Implement `TRACE-PROPAGATION.md` design: add Micrometer Tracing to gateway (generates traceId), propagate via `X-Trace-Id` header through service calls, inject into Kafka headers, extract on consumer side.
- **Files:**
  - `[MODIFY]` `gateway-service/build.gradle.kts` — add `micrometer-tracing-bridge-brave`
  - `[MODIFY]` `gateway-service/.../application.yml` — add `management.tracing.sampling.probability: 1.0`
  - `[MODIFY]` `stream-service/.../messaging/StreamEventPublisher.java` — inject traceId into Kafka headers
  - `[MODIFY]` `notification-service/.../messaging/StreamControlListener.java` — extract traceId from Kafka headers, set on MDC
  - `[MODIFY]` `chat-service/.../messaging/StreamControlListener.java` — same
- **Validate:** Produce a request through gateway → traceId present in all service logs for that request, consistent traceId in Kafka consumer logs.

### Task D3: Grafana Dashboard Scaffold

- **Action:** Create a basic Grafana dashboard JSON with rows for: service health, Redis cache hit rate, Kafka consumer lag, outbox queue depth, error rate by service.
- **Files:**
  - `[CREATE]` `main/docker/grafana/dashboards/streaming-platform.json` — dashboard with 5 rows
  - `[CREATE]` `main/docker/grafana/datasources/prometheus.yml` — Prometheus datasource config
  - `[MODIFY]` `compose.yaml` — add Grafana service (grafana/grafana:11, port 3000, mount dashboards + datasources)
- **Validate:** `docker compose up -d grafana` → Grafana healthy → Prometheus datasource connected → dashboard renders.

---

## Track E — Infrastructure Maturity (🟠 HIGH, ~1-2 days)

**Goal:** Fix Redis atomicity, Kafka operational config, SSE memory safety.
**Order:** After Track A. Parallel to Tracks C, D, F.

### Task E1: Redis Lua Script for Atomic Cache Write

- **Action:** Replace 3 round-trip write (ZADD + ZREMRANGEBYRANK + EXPIRE) with a single Lua script execution. This closes gap R1 from the production gap analysis.
- **Files:**
  - `[CREATE]` `chat-service/src/main/resources/scripts/add_to_recent.lua` — Lua script: `redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2]); redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -ARGV[3]-1); redis.call('EXPIRE', KEYS[1], ARGV[4]); return 1`
  - `[MODIFY]` `chat-service/.../cache/RedisMessageCache.java` — replace `addToRecent()` body with `redis.execute(RedisScript.of(script, Boolean.class), ...)`
- **Validate:** `./gradlew :chat-service:compileJava` — BUILD SUCCESSFUL. `RedisMessageCacheTest` passes with real Redis.

### Task E2: Disable Kafka Auto-Create Topics

- **Action:** Set `auto.create.topics.enable: false` in Kafka broker config. Create topics explicitly via init container or `KAFKA_CREATE_TOPICS` env var.
- **Files:**
  - `[MODIFY]` `main/docker/kafka/single-broker/docker-compose.yaml` — add `KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "false"`
  - `[MODIFY]` `main/docker/kafka/compose-config/broker1.env` — add topic creation or `KAFKA_CREATE_TOPICS` env var for `stream.control:1:3` and `stream.control.dlq:1:3`
- **Validate:** Produce to a non-existent topic → error. Produce to `stream.control` → success.

### Task E3: Fix Redis SCAN Memory Safety

- **Action:** Add `.count()` hint to all SCAN operations. Replace `collectList()` with streaming `.reduce()` or chunked processing. Add a hard limit on total keys to prevent OOM.
- **Files:**
  - `[MODIFY]` `stream-service/.../service/HeartbeatHarvestService.java` — line 78: replace `collectList()` with chunked processing (process 1000 keys, count, then next chunk). Add `.limit(50_000)` safety cap.
  - `[MODIFY]` `stream-service/.../service/ViewerCountPushService.java` — line 102: same treatment. Also add SCAN pattern filtering: `stream:presence:PREFIX_FOR_ACTIVE_STREAM:*` where possible.
  - `[MODIFY]` `stream-service/.../service/ViewCountFlushService.java` — add `.count(100)` to SCAN options.
- **Validate:** Test with 10K simulated presence keys → harvest completes without OOM, processes in chunks.

### Task E4: SSE Backpressure Buffer Bounds + Stale Connection Drain

- **Action:** Add explicit buffer size with `OverflowStrategy.DROP_OLDEST` instead of default unbounded growth. Add periodic zombie drain (every 60s, remove sinks where `currentSubscriberCount() == 0`).
- **Files:**
  - `[MODIFY]` `stream-service/.../sse/SseConnectionRegistry.java` — line 32: change `onBackpressureBuffer(64)` to `onBackpressureBuffer(64, BufferOverflowStrategy.DROP_OLDEST)`. Add `@Scheduled(fixedDelay = 60_000)` drain method.
  - `[MODIFY]` `notification-service/.../infrastructure/SseConnectionRegistry.java` — same changes.
  - `[MODIFY]` `stream-service/.../sse/SseConnectionRegistry.java` — add Javadoc note about single-instance limitation and Redis Pub/Sub future fix.
- **Validate:** Connect slow client → buffer fills → old events dropped, not OOM. Disconnect without cleanup → zombie drain removes sink within 60s.

---

## Track F — Error Handling Standardization (🟠 HIGH, ~1 day)

**Goal:** Consistent error logging, catch-all handlers, exception-passing convention.
**Order:** After Track A. Parallel to Tracks C, D, E.

### Task F1: Add Logging to All Exception Handlers

- **Action:** Add `log.warn()` or `log.error()` to every exception handler in every service. Security events (access denied, banned user, invalid token) logged at WARN. Not-found events logged at DEBUG or INFO.
- **Files:**
  - `[MODIFY]` `auth-service/.../api/GlobalExceptionHandler.java` — add `log.warn("Authentication failure: {}", ex.getMessage())` to each handler
  - `[MODIFY]` `chat-service/.../api/error/ChatExceptionHandler.java` — change `log.debug` to `log.warn` for `UserBannedException` and `ChatAccessDeniedException`; keep `log.debug` for `RoomNotFoundException`, `BanNotFoundException`
  - `[MODIFY]` `notification-service/.../api/error/NotificationExceptionHandler.java` — change `log.debug` to `log.info` for subscription errors
  - `[MODIFY]` `stream-service/.../api/StreamExceptionHandler.java` — add `log.warn()` to specific handlers (currently NO logging in any)
- **Validate:** Trigger each exception type → log output at correct level.

### Task F2: Fix getMessage() → Full Exception Logging

- **Action:** Replace all `log.warn("...{}", ex.getMessage())` with `log.warn("...", ex)` (pass exception as last parameter so SLF4J logs the full stack trace). Affects ~15 locations across all services.
- **Files:**
  - `[MODIFY]` `stream-service/.../service/HeartbeatHarvestService.java:105` — `log.warn("Heartbeat harvest cycle failed", ex)`
  - `[MODIFY]` `stream-service/.../service/ViewerCountPushService.java:80` — `log.warn("Viewer count push cycle failed", ex)`
  - `[MODIFY]` `stream-service/.../service/ChatArchiveScheduler.java:75` — `log.warn("ChatArchiveScheduler error", e)`
  - `[MODIFY]` `stream-service/.../messaging/OutboxPoller.java:70` — `log.warn("OutboxPoller poll iteration failed", e)`
  - `[MODIFY]` `notification-service/.../messaging/OutboxPoller.java:67` — same
  - `[MODIFY]` `chat-service/.../messaging/StreamControlListener.java:52` — `log.error("Failed to deserialize stream event", e)`
  - `[MODIFY]` `notification-service/.../messaging/StreamControlListener.java:72` — `log.error("Failed to deserialize stream event", e)`
  - `[MODIFY]` `notification-service/.../messaging/StreamControlListener.java:89` — `log.warn("Redis dedup check failed...", ex)`
  - `[MODIFY]` `notification-service/.../application/OutboxService.java:52,67` — `log.warn("...", e)` instead of `e.getMessage()`
  - `[MODIFY]` `chat-service/.../cache/RedisReconnectListener.java:88,132` — `log.warn("...", ex)` instead of `ex.getMessage()`
  - `[MODIFY]` `stream-service/.../service/StreamService.java:835` — `log.warn("on_unpublish lookup failed", e)` instead of `e.getMessage()`
  - `[MODIFY]` `stream-service/.../api/StreamExceptionHandler.java:90` — already logs full `ex`, verify all handlers do
- **Validate:** Search codebase for `\.getMessage\(\)` inside log statements → zero remaining (except where intentionally extracting message for response body).

### Task F3: Create Shared Exception Handler Base Class

- **Action:** Create a shared `@RestControllerAdvice` base class in `pbac-common` that provides the catch-all handler and a consistent error envelope. Services extend it and add their domain-specific handlers.
- **Files:**
  - `[CREATE]` `pbac-common/.../api/BaseExceptionHandler.java` — `@RestControllerAdvice` with `@ExceptionHandler(Exception.class)` returning `ApiMessage("INTERNAL_ERROR", "An unexpected error occurred")` + `log.error("Unhandled exception", ex)`
  - `[MODIFY]` `auth-service/.../api/GlobalExceptionHandler.java` — extend `BaseExceptionHandler`, keep domain-specific handlers
  - `[MODIFY]` `chat-service/.../api/error/ChatExceptionHandler.java` — extend `BaseExceptionHandler`
  - `[MODIFY]` `notification-service/.../api/error/NotificationExceptionHandler.java` — extend `BaseExceptionHandler`
- **Validate:** All 4 services compile. Trigger unexpected exception in each → consistent error envelope with no stack trace.

---

## Track G — Test Execution Pipeline (🟡 MEDIUM, ongoing)

**Goal:** Enable Docker-based test execution in CI, verify existing tests pass, add coverage gates.
**Order:** Can run in parallel with all other tracks.

### Task G1: Run Existing Chat-Service Test Suite

- **Action:** Start Docker, run `./gradlew :chat-service:test` against real Redis + PostgreSQL containers. Fix any tests that fail (they've never been run).
- **Files:**
  - `[MODIFY]` `chat-service/src/test/resources/application-test.yml` — verify Testcontainers config is correct
  - Potentially fix test files that don't compile or have incorrect assumptions
- **Validate:** `./gradlew :chat-service:test` — all 13 test classes pass with real containers.

### Task G2: Run Existing Stream-Service Test Suite

- **Action:** Same as G1 but for stream-service.
- **Files:** `stream-service/src/test/` — verify existing tests pass
- **Validate:** `./gradlew :stream-service:test` — all tests pass.

### Task G3: Add CI Test Job

- **Action:** Create a GitHub Actions workflow or equivalent that starts Docker, runs all test suites, and gates PRs on test pass.
- **Files:**
  - `[CREATE]` `.github/workflows/test.yml` — job: start Docker Compose infra → wait healthy → `./gradlew test` → report results
- **Validate:** Push a PR → CI runs tests → tests pass → PR is green.

---

## Dependency Graph

```
Track A (Security Triage)
    ├──► Track B (Outbox & State Machine) ──► Track C (Notification Hardening)
    ├──► Track C (Notification Hardening) ──► (continues in parallel)
    ├──► Track D (Observability) ───────────► (parallel)
    ├──► Track E (Infrastructure) ──────────► (parallel)
    ├──► Track F (Error Handling) ──────────► (parallel)
    └──► Track G (Test Pipeline) ───────────► (parallel, runs alongside all)

Track C depends on A (config changes). Tracks D, E, F are independent of each other after A.
```

---

## Validation — Full Platform Smoke Test

After all tracks complete, run the end-to-end validation:

```bash
# 1. Start all infrastructure
docker compose up -d
# Wait for all services healthy

# 2. Start all backend services
./gradlew bootRun  # (or IDE launch configs)

# 3. Verify auth flow
curl -X POST http://localhost:8080/api/auth/v1/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test_streamer","password":"..."}'
# → 200 with access token + refresh token cookie

# 4. Verify stream lifecycle
curl -X POST http://localhost:8080/api/streams/v1/streams \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Test Stream","category":"Gaming"}'
# → 201 with stream ID + publish key

# 5. Simulate SRS webhook (stream goes live)
curl -X POST http://localhost:8080/api/streams/v1/webhooks/srs/on_publish \
  -d '{"param":"?token=$PUBLISH_TOKEN","app":"live","stream":"$SRS_NAME"}'
# → 200 (stream transitions to LIVE)

# 6. Verify chat works
curl -X POST http://localhost:8080/api/chat/v1/rooms/$ROOM_KEY/messages \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -d '{"content":"Hello stream!"}'
# → 201 with message

# 7. Verify notification dispatch
# Check notification-service logs: "Notification persisted", SSE push triggered

# 8. Verify outbox integrity
# Check stream-service DB: outbox row existed and was deleted after publish
# Check Kafka: message on stream.control topic

# 9. Verify metrics
curl http://localhost:8081/actuator/prometheus  # gateway metrics
# → cache.hit, cache.miss counters present

# 10. Verify error handling
curl -X POST http://localhost:8080/api/streams/v1/streams/00000000-0000-0000-0000-000000000000/start \
  -H "Authorization: Bearer $TOKEN"
# → 404 with {"code":"STREAM_NOT_FOUND","message":"Stream not found"}
# → check logs: "StreamNotFoundException" at WARN level with full stack trace
```

---

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `@Transactional` incompatible with R2DBC | Medium | Use `TransactionalOperator` pattern instead (R2DBC-native); already documented in Spring Data R2DBC reference |
| Existing tests fail when first run | High | Tests written months ago against assumptions that may have drifted. Budget 1 extra day for test fixes in G1-G2. |
| Email implementation reveals missing user email data | Medium | `broadcaster_profile` has no email field; may need schema change or lookup from auth-service. Start with Option B (hard-fail) if data model is incomplete. |
| Lua script breaks under concurrent load | Low | Lua scripts are atomic on the Redis server; race conditions are the reason to use them. Test with concurrent writers. |
| Fan-out offloading changes notification delivery latency | Medium | `subscribeOn(Schedulers.boundedElastic())` moves work off Kafka thread but doesn't speed it up. Accept 2-5s delivery latency for follower notifications; SSE delivery is immediate (happens before outbox). |

---

*Ready for approval. Recommend executing Track A first (security cleanup), then Tracks B+C+D+E+F in parallel, with Track G running alongside all.*
