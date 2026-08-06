# Blueprint: Fan-Out Job Queue

**Date**: 2026-08-03
**Status**: draft
**Deciders**: hieuht, Claude

## Summary

Replace the inline `deliverToMany()` fan-out in `StreamControlListener.onStreamStarted()` with an async two-phase job queue: **enqueue** (one INSERT, ~2ms, consumer returns immediately) → **process** (a scheduled poller picks up PENDING jobs, loads subscribers in chunks, processes each chunk with controlled concurrency and retry). This teaches every core job queue concept — enqueue/dequeue, ACK/NACK, batching, partial failure, dead-letter, visibility timeout — on infrastructure you already own (PostgreSQL + R2DBC).

## Learning Objectives (Job Queue Concepts Mapped)

| Concept | Where you'll implement it |
|---------|---------------------------|
| **Enqueue** | `FanOutService.enqueue()` — one INSERT, returns immediately |
| **Dequeue + claim** | `FOR UPDATE SKIP LOCKED LIMIT 1` — atomic row-level lock |
| **Batching** | Load subscriber list, `Iterables.partition(subscribers, batchSize)` → chunks |
| **Concurrency control** | `Flux.fromIterable(chunks).flatMap(processChunk, maxConcurrency)` |
| **ACK** | `UPDATE fan_out_job SET state = 'COMPLETED'` |
| **NACK + retry** | `UPDATE fan_out_job SET state = 'FAILED', retry_count++` |
| **Dead Letter Queue** | After maxRetries: `UPDATE fan_out_job SET state = 'DEAD'` |
| **Partial failure** | A single chunk fails → retry only that chunk, not the whole job |
| **Visibility timeout** | `claimed_at` + `claimed_by` columns — detect stuck jobs (worker crashed) |
| **Scheduled jobs** | `scheduled_at` column — "don't process this until…" (enables reminders later) |
| **Idempotency** | Unique constraint on `(event_id, job_type)` — replay the same Kafka event → skip |
| **Observability** | Query `COUNT(*) WHERE state = 'PENDING'` for queue depth; `claimed_at` for stuck-jobs detection |

## Patterns to Mirror

| Category | Source | Pattern |
|----------|--------|---------|
| Entity + factory | `domain/OutboxEntry.java:81` | `Persistable<UUID>` + `@Transient isNew` + static `create()` factory |
| Repository | `persistence/ReactiveOutboxRepository.java:30` | `ReactiveCrudRepository` + `@Query` with `FOR UPDATE SKIP LOCKED` |
| Poller structure | `messaging/OutboxPoller.java:57` | `@Scheduled` void method → reactive chain → `.blockOptional(Duration)` |
| Poller retry/DEAD | `messaging/OutboxPoller.java:100` | `handleFailure()`: retry count check → DEAD or FAILED state |
| Config properties | `application.yml:62` | `notification.outbox.*` prefix → same convention for `notification.fanout.*` |
| Flyway migration | `db/migration/V5__*.sql` | `ALTER TABLE` / `CREATE TABLE` with `IF NOT EXISTS`, schema-qualified names |
| Test structure | `stream-service` `OutboxPoller` test pattern | Mock repository + verify interactions |
| Error handling | all pollers | `onErrorResume` — never let one failed poll kill the scheduler |

## New Files

| File | Purpose |
|------|---------|
| `V6__create_fanout_job_table.sql` | Flyway migration: `notification.fan_out_job` table + indexes |
| `domain/FanOutJob.java` | R2DBC entity: `Persistable<UUID>`, states, factory method, domain methods |
| `infrastructure/persistence/ReactiveFanOutJobRepository.java` | R2DBC repo: `pollPending()`, `updateState()`, `claimJob()` |
| `application/FanOutService.java` | Enqueue a `FanOutJob` (called from `StreamControlListener`) |
| `messaging/FanOutPoller.java` | `@Scheduled` worker: claim → load subscribers → chunk → dispatch → ACK/NACK |

## Modified Files

| File | Change | Why |
|------|--------|-----|
| `messaging/StreamControlListener.java` | Replace `deliverToMany()` with `fanOutService.enqueue()` in `onStreamStarted()` | The Kafka consumer returns in ~2ms instead of blocking for N followers |
| `application.yml` | Add `notification.fanout.*` properties | Configurable batch size, concurrency, poll interval |

## Tasks

### Task 1: Flyway Migration (V6)

- **Action**: Create `V6__create_fanout_job_table.sql` with:
  ```sql
  CREATE TABLE IF NOT EXISTS notification.fan_out_job (
      id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      event_id          VARCHAR(128) NOT NULL,        -- Kafka StreamEvent.eventId (dedup)
      job_type          VARCHAR(64) NOT NULL,          -- "STREAM_STARTED" (future: "STREAM_ENDED")
      broadcaster_subject VARCHAR(128) NOT NULL,       -- who went live
      target_type       VARCHAR(64) NOT NULL,          -- "CHANNEL"
      target_id         VARCHAR(128) NOT NULL,         -- broadcaster subject (redundant, fast lookup)
      state             VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING|PROCESSING|COMPLETED|FAILED|DEAD
      total_subscribers  INTEGER,                      -- snapshot at enqueue time (for progress)
      processed_subscribers INTEGER NOT NULL DEFAULT 0, -- running count as chunks complete
      retry_count       INTEGER NOT NULL DEFAULT 0,
      claimed_at        TIMESTAMPTZ,                   -- visibility timeout: when a worker claimed it
      claimed_by        VARCHAR(128),                  -- worker instance ID (for debugging)
      last_error        TEXT,                          -- last failure reason (for DEAD inspection)
      scheduled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),  -- for future: delayed processing
      created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```
  Plus indexes:
  - `(state, scheduled_at)` — poller's primary scan
  - `(event_id, job_type)` unique — idempotency guard
  - `(claimed_at)` — stuck-job detection queries

- **Files**: `src/main/resources/db/migration/V6__create_fanout_job_table.sql`
- **Validate**: Run the notification-service, check Flyway logs for successful V6 migration

### Task 2: FanOutJob Domain Entity

- **Action**: Create `FanOutJob.java` in `domain/` — mirrors `OutboxEntry.java`:
  - `Persistable<UUID>` + `@Transient boolean isNew`
  - Fields matching the schema above
  - Static `create(eventId, jobType, broadcasterSubject, targetType, targetId, now)` factory
  - Domain methods: `claim(instanceId, now)`, `markCompleted(now)`, `recordProgress(count, now)`, `recordFailure(error, now)`, `markDead(now)`
  - States as constants: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `DEAD`
  - `@Table(name = "fan_out_job")`

- **Files**: `src/main/java/.../domain/FanOutJob.java`
- **Validate**: Compiles. Follows same pattern as `OutboxEntry.java` (Lombok, Persistable, static factory).

### Task 3: ReactiveFanOutJobRepository

- **Action**: Create `ReactiveFanOutJobRepository.java` in `infrastructure/persistence/`:
  - Extends `ReactiveCrudRepository<FanOutJob, UUID>`
  - `pollPending(limit)` — `SELECT ... WHERE state = 'PENDING' AND scheduled_at <= now() ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED`
  - `claimJob(id, instanceId, now)` — atomic claim: set `state = 'PROCESSING'`, `claimed_at = now`, `claimed_by = instanceId` WHERE `state = 'PENDING'`
  - `updateState(id, state, retryCount, processedCount, lastError, now)` — bulk update after processing
  - `existsByEventIdAndJobType(eventId, jobType)` — dedup check before enqueue
  - `findStuckJobs(stuckThreshold)` — `WHERE state = 'PROCESSING' AND claimed_at < now() - interval` (for stuck-job recovery — future)

- **Files**: `src/main/java/.../infrastructure/persistence/ReactiveFanOutJobRepository.java`
- **Validate**: Compiles. Query methods follow ReactCrudRepository conventions.

### Task 4: FanOutService (Enqueue)

- **Action**: Create `FanOutService.java` in `application/`:
  ```java
  @Service
  public class FanOutService {
      enqueue(StreamEvent event):
          1. Check dedup: repository.existsByEventIdAndJobType(event.eventId(), "STREAM_STARTED")
             → if exists, log and return Mono.empty() (idempotent)
          2. Count subscribers (for progress tracking)
          3. Create FanOutJob via factory
          4. Save to repository
          5. Log "Fan-out job enqueued: jobId={} subscribers={} broadcaster={}"
          6. Return Mono<Void> (completes when INSERT succeeds, ~2ms)
  }
  ```
  - Constructor-injected dependencies: `ReactiveFanOutJobRepository`, `ReactiveSubscriptionRepository`
  - Follows `OutboxService` pattern: static factory for entity, fire-and-forget save with error logging

- **Files**: `src/main/java/.../application/FanOutService.java`
- **Validate**: Compiles. Returns `Mono<Void>` — callers chain with `.then()`.

### Task 5: FanOutPoller (Worker)

- **Action**: Create `FanOutPoller.java` in `messaging/` — the core job queue worker:
  ```java
  @Component
  public class FanOutPoller {
      @Scheduled(fixedDelayString = "${notification.fanout.poll-interval:5000}")
      void poll():
          1. claimAndProcess() — pick up one PENDING job (single job per poll to keep it simple)
          2. load subscribers via SubscriptionService.getSubscribers(targetType, targetId)
          3. chunk subscribers by batchSize (default 100)
          4. Flux.fromIterable(chunks).flatMap(processChunk, maxConcurrency):
             a. For each sub in chunk: notificationService.createForFollower(event, sub)
             b. dispatcher.deliverToMany(chunkNotifications, concurrency)
             c. On success: increment processedSubscribers on FanOutJob
             d. On failure: record error, retry chunk on next poll cycle
          5. All chunks done → mark COMPLETED
          6. Any chunk fails after maxRetries → mark DEAD with lastError
          7. Every path → .blockOptional(Duration.ofSeconds(60))
  }
  ```
  - Constructor-injected: `ReactiveFanOutJobRepository`, `SubscriptionService`, `NotificationService`, `NotificationDispatcher`, `@Value` for config
  - Mirrors `OutboxPoller` structure: `@Scheduled` → reactive chain → `.blockOptional()`
  - `onErrorResume` at the top level: never let one failed poll kill the scheduler
  - **Key learning point**: This is where all the job queue concepts come together — claim, batch, process, ACK, NACK, DLQ, partial failure

- **Files**: `src/main/java/.../messaging/FanOutPoller.java`
- **Validate**: Compiles. Mirrors OutboxPoller's structure and error-handling patterns.

### Task 6: Modify StreamControlListener

- **Action**: In `onStreamStarted()`:
  - **Remove**: the `subscriptionService.getSubscribers()` → `createForFollower()` → `collectList()` → `deliverToMany()` chain
  - **Replace with**: `fanOutService.enqueue(event)` — one call, ~2ms
  - The broadcaster self-notification (`createFromStreamEvent`) stays unchanged
  - Add `FanOutService` to constructor (Lombok `@RequiredArgsConstructor` handles it)

- **Before** (lines 139-153):
  ```java
  Mono<Void> fanOut = subscriptionService
          .getSubscribers("CHANNEL", event.broadcasterSubject())
          .flatMap(sub -> notificationService.createForFollower(...))
          .collectList()
          .flatMap(notifications -> {
              if (notifications.isEmpty()) { ... return Mono.empty(); }
              return dispatcher.deliverToMany(notifications, 8);
          });
  ```

- **After**:
  ```java
  Mono<Void> fanOut = fanOutService.enqueue(event);
  ```

- **Files**: `src/main/java/.../messaging/StreamControlListener.java`
- **Validate**: Compiles. `onStreamStarted()` is ~5 lines instead of ~20.

### Task 7: Configuration

- **Action**: Add to `application.yml`:
  ```yaml
  notification:
    fanout:
      poll-interval: ${NOTIFICATION_FANOUT_POLL_INTERVAL:5000}
      batch-size: ${NOTIFICATION_FANOUT_BATCH_SIZE:100}
      max-concurrency: ${NOTIFICATION_FANOUT_MAX_CONCURRENCY:4}
      max-retries: ${NOTIFICATION_FANOUT_MAX_RETRIES:3}
  ```

- **Files**: `src/main/resources/application.yml`
- **Validate**: `FanOutPoller` reads properties correctly at startup.

### Task 8: Tests

- **Action**: Create two test classes:
  1. **FanOutServiceTest**: mock `ReactiveFanOutJobRepository` + `ReactiveSubscriptionRepository`, verify:
     - `enqueue()` saves a FanOutJob with correct state=PENDING
     - Duplicate `eventId` → skipped (idempotency)
     - Repository save failure → logged, no exception propagated
  2. **FanOutPollerTest**: mock all dependencies, verify:
     - Single PENDING job → claimed → processed → marked COMPLETED
     - Zero subscribers → job marked COMPLETED with processed=0
     - Partial chunk failure → retry logic, DEAD after maxRetries
     - No PENDING jobs → poll is a no-op
     - Exception during processing → caught, logged, scheduler survives

- **Files**:
  - `src/test/java/.../application/FanOutServiceTest.java`
  - `src/test/java/.../messaging/FanOutPollerTest.java`
- **Validate**: `./gradlew :notification-service:test --tests "*FanOut*"` passes

## Architecture Diagram (After)

```
Kafka message arrives (STREAM_STARTED)
  │
  ▼
StreamControlListener.onStreamStarted(event)
  │
  ├─ broadcasterNotification:
  │    createFromStreamEvent() → dispatcher.deliver(n)
  │      ├─ persist PG
  │      ├─ SSE push
  │      └─ outbox enqueue (email later)
  │
  └─ fanOut:                                              ← ⚡ CHANGED
       fanOutService.enqueue(event)                        ← ONE INSERT, ~2ms
            │
            ▼
       notification.fan_out_job (state=PENDING)
            │
            ▼  (up to 5s later)
       FanOutPoller.poll()
            │
            ├─ SELECT ... FOR UPDATE SKIP LOCKED           ← claim job
            ├─ state → PROCESSING
            ├─ load subscribers
            ├─ chunk by batchSize (100)
            ├─ for each chunk:
            │    dispatcher.deliverToMany(chunk, concurrency)
            │    ├─ persist PG (N INSERTs)
            │    ├─ SSE push (N pushes)
            │    └─ outbox enqueue (N rows, for email)
            ├─ all chunks done → state = COMPLETED          ← ACK
            └─ chunk fails:
                 ├─ retry < maxRetries → state = FAILED     ← NACK
                 └─ retry >= maxRetries → state = DEAD      ← DLQ
```

## Data Flow Comparison

| | Before (inline) | After (job queue) |
|---|---|---|
| **Consumer blocked for** | N followers × (persist + SSE + outbox) / 8 concurrency | One INSERT (~2ms) |
| **Fan-out latency** | Immediate | Up to poll interval (5s) + processing time |
| **Survives crash?** | No — in-memory work lost | Yes — jobs are durable in PG |
| **Retry on failure?** | No — exception kills the chain | Yes — retry up to 3x, then DEAD |
| **Can see progress?** | No | Yes — `SELECT processed_subscribers, total_subscribers` |
| **Can scale workers?** | No — single consumer thread | Yes — multiple poller instances with SKIP LOCKED |
| **Idempotent?** | No — Kafka replay = duplicate fan-out | Yes — unique constraint on (event_id, job_type) |

## Config Properties Reference

```yaml
notification:
  fanout:
    poll-interval: 5000        # ms between poll cycles (job queue latency)
    batch-size: 100            # subscribers per chunk (unit of retry)
    max-concurrency: 4         # max concurrent chunk processing
    max-retries: 3             # retries before DEAD letter
```

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| FanOutPoller crashes mid-chunk | Medium | `claimed_at` timeout → another poller instance re-claims stuck jobs (future enhancement; for MVP, restart re-processes) |
| Subscriber list changes between enqueue and process | Low | Snapshot at processing time (query subscribers when poller runs, not at enqueue time). A follower added after enqueue still gets the notification. |
| Outbox table growth (N subscribers × outbox rows) | Medium | Same as current behavior — the outbox grows anyway. FanOutPoller just moves the writes from the consumer thread to a worker thread. |
| Poller latency (5s + processing) | Low | For email, 5s is invisible (SMTP takes 500ms-2s). For SSE, the broadcaster self-notification (Step 1 in `onStreamStarted`) still delivers immediately to the broadcaster. Followers get in-app notifications within seconds. |

## Validation

```bash
# Build check
./gradlew :notification-service:compileJava

# Run FanOut-specific tests
./gradlew :notification-service:test --tests "*FanOut*"

# Run all notification-service tests to verify no regressions
./gradlew :notification-service:test

# Start the service and verify Flyway migration
docker compose up -d postgres
./gradlew :notification-service:bootRun
# Check logs for: "Successfully applied migration V6"
# Check logs for: "Fan-out poller started"
```

## Future Extensions (not in this blueprint)

Once the core job queue is working, these become natural next steps:

1. **Visibility timeout recovery**: A poller that scans for `state = 'PROCESSING' AND claimed_at < now() - 5min` and re-claims stuck jobs
2. **`scheduled_at` for reminders**: Stream-scheduled events create FanOutJobs with `scheduled_at = stream_start - 30min` — the poller query already filters on `scheduled_at <= now()`
3. **Fan-out for STREAM_ENDED**: Same pattern, different job_type
4. **Admin dashboard endpoint**: `GET /v1/admin/fanout/stats` — queue depth, processing rate, DEAD jobs
5. **Multiple poller instances**: The `SKIP LOCKED` pattern already supports this — just deploy 2+ instances
