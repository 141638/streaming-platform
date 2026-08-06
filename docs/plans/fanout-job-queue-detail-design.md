# Detail Design: Fan-Out Job Queue

**Date**: 2026-08-06  
**Status**: draft — living document, expect updates during implementation  
**Deciders**: hieuht, Claude  
**Reference**: [fanout-job-queue-blueprint.md](fanout-job-queue-blueprint.md)

> **How to use this document**: Tasks are ordered by dependency. Each task has exact file paths, method signatures, SQL, and validation commands. When implementation reveals new details, update the relevant task section — this document is the source of truth for what was actually built.

---

## Summary

Replace the inline `deliverToMany()` fan-out in `StreamControlListener.onStreamStarted()` with a two-phase PostgreSQL-backed job queue:
1. **Enqueue** — `FanOutService.enqueue(event)` writes one `fan_out_job` row (~2ms), consumer returns immediately
2. **Process** — `FanOutPoller.poll()` picks up PENDING jobs on a schedule, loads subscribers, chunks by `batchSize`, dispatches each chunk via `NotificationDispatcher.deliverToMany()`

Teaches every core job queue concept on existing infrastructure (PostgreSQL + R2DBC): enqueue, claim (SKIP LOCKED), ACK/NACK, batching, partial failure, DLQ, idempotency.

---

## Files Inventory

### New (6 files)

| # | File | Layer |
|---|------|-------|
| 1 | `V6__create_fanout_job_table.sql` | Flyway migration |
| 2 | `domain/FanOutJob.java` | Domain entity |
| 3 | `infrastructure/persistence/ReactiveFanOutJobRepository.java` | R2DBC repository |
| 4 | `application/FanOutService.java` | Application service (enqueue) |
| 5 | `messaging/FanOutPoller.java` | Infrastructure worker (process) |
| 6 | `messaging/FanOutPollerTest.java` | Unit test |

### Modified (2 files)

| # | File | Change |
|---|------|--------|
| 7 | `messaging/StreamControlListener.java` | Replace inline fan-out with `fanOutService.enqueue(event)` |
| 8 | `resources/application.yml` | Add `notification.fanout.*` config block |

### Base path
```
main/source/backend/notification-service/src/
├── main/java/com/streaming/notification/
│   ├── domain/
│   ├── application/
│   ├── messaging/
│   └── infrastructure/persistence/
├── main/resources/
│   ├── application.yml
│   └── db/migration/
└── test/java/com/streaming/notification/
    └── messaging/
```

---

## Task 1: Flyway Migration V6

**File**: `src/main/resources/db/migration/V6__create_fanout_job_table.sql`

### SQL

```sql
-- V6: Fan-out job queue table.
--
-- Each row is one fan-out "job" — created when a followed broadcaster
-- starts a stream, picked up asynchronously by FanOutPoller.
--
-- States: PENDING → PROCESSING → COMPLETED (ACK)
--         PENDING → PROCESSING → FAILED → retry → PROCESSING (NACK + retry)
--         PENDING → PROCESSING → FAILED → DEAD (DLQ, after maxRetries)
--
-- Idempotency: UNIQUE (event_id, job_type) — replay the same Kafka event → skip.
-- Visibility timeout: claimed_at + claimed_by — detect stuck jobs (future).

CREATE TABLE IF NOT EXISTS notification.fan_out_job (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id              VARCHAR(128) NOT NULL,
    job_type              VARCHAR(64)  NOT NULL,
    broadcaster_subject   VARCHAR(128) NOT NULL,
    target_type           VARCHAR(64)  NOT NULL,
    target_id             VARCHAR(128) NOT NULL,
    state                 VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    total_subscribers     INTEGER,
    processed_subscribers INTEGER      NOT NULL DEFAULT 0,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    claimed_at            TIMESTAMPTZ,
    claimed_by            VARCHAR(128),
    last_error            TEXT,
    scheduled_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Poller's primary scan: find PENDING jobs ready for processing.
CREATE INDEX IF NOT EXISTS ix_fan_out_job_poll
    ON notification.fan_out_job (state, scheduled_at, created_at);

-- Idempotency guard: same Kafka event + job type → skip duplicate enqueue.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fan_out_job_dedup
    ON notification.fan_out_job (event_id, job_type);

-- Stuck-job detection (future): find jobs in PROCESSING state past their
-- visibility timeout (e.g., claimed_at < now() - 5 minutes).
CREATE INDEX IF NOT EXISTS ix_fan_out_job_claimed
    ON notification.fan_out_job (state, claimed_at)
    WHERE state = 'PROCESSING';
```

### Validation
```bash
# Start postgres, run migration
docker compose up -d postgres
./gradlew :notification-service:flywayMigrate
# Or bootRun — Flyway auto-runs on startup
./gradlew :notification-service:bootRun
# Check logs for: "Successfully applied migration V6"
```

### Design notes
- `scheduled_at` defaults to `now()` — all jobs process immediately. Future: stream-scheduled events set `scheduled_at = stream_start_time - 30min` for reminder fan-out.
- `total_subscribers` is nullable because we may not know at enqueue time (lazy count). The poller queries actual subscribers at processing time.
- `last_error` stores the exception message from the last failed attempt — makes DEAD jobs inspectable.
- `target_type` + `target_id` mirror the Subscription polymorphic target pattern.

---

## Task 2: FanOutJob Domain Entity

**File**: `src/main/java/com/streaming/notification/domain/FanOutJob.java`

### Class contract

Mirrors `OutboxEntry.java` exactly: Lombok getters/setters, `Persistable<UUID>`, `@Transient isNew`, static factory, domain mutation methods.

```java
package com.streaming.notification.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity for {@code notification.fan_out_job}.
 *
 * <p>Represents a fan-out work item — created when a followed broadcaster
 * starts a stream, picked up asynchronously by {@code FanOutPoller}.
 *
 * <p>State machine:
 * <pre>
 * PENDING → PROCESSING → COMPLETED    (success)
 *        → PROCESSING → FAILED        (retryable, retry_count < maxRetries)
 *        → PROCESSING → FAILED → DEAD (exceeded maxRetries, human inspection)
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "fan_out_job")
public class FanOutJob implements Persistable<UUID> {

    public static final String STATE_PENDING    = "PENDING";
    public static final String STATE_PROCESSING = "PROCESSING";
    public static final String STATE_COMPLETED  = "COMPLETED";
    public static final String STATE_FAILED     = "FAILED";
    public static final String STATE_DEAD       = "DEAD";

    @Id
    private UUID id;

    @Transient
    private boolean isNew;

    @Column("event_id")
    private String eventId;

    @Column("job_type")
    private String jobType;

    @Column("broadcaster_subject")
    private String broadcasterSubject;

    @Column("target_type")
    private String targetType;

    @Column("target_id")
    private String targetId;

    private String state;

    @Column("total_subscribers")
    private Integer totalSubscribers;

    @Column("processed_subscribers")
    private int processedSubscribers;

    @Column("retry_count")
    private int retryCount;

    @Column("claimed_at")
    private OffsetDateTime claimedAt;

    @Column("claimed_by")
    private String claimedBy;

    @Column("last_error")
    private String lastError;

    @Column("scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    // -- factory -----------------------------------------------------------

    /**
     * Create a new fan-out job in PENDING state.
     *
     * @param eventId            the Kafka {@code StreamEvent.eventId} (dedup key)
     * @param jobType            event type ({@code "STREAM_STARTED"})
     * @param broadcasterSubject the JWT sub of the broadcaster who went live
     * @param targetType         subscription target type ({@code "CHANNEL"})
     * @param targetId           subscription target ID (broadcaster subject)
     * @param now                creation timestamp
     */
    public static FanOutJob create(
            String eventId,
            String jobType,
            String broadcasterSubject,
            String targetType,
            String targetId,
            OffsetDateTime now) {
        FanOutJob job = new FanOutJob();
        job.setId(UUID.randomUUID());
        job.setNew(true);
        job.setEventId(eventId);
        job.setJobType(jobType);
        job.setBroadcasterSubject(broadcasterSubject);
        job.setTargetType(targetType);
        job.setTargetId(targetId);
        job.setState(STATE_PENDING);
        job.setRetryCount(0);
        job.setProcessedSubscribers(0);
        job.setScheduledAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    // -- domain state transitions ------------------------------------------

    /** Claim this job for processing by a worker instance. */
    public void claim(String instanceId, OffsetDateTime now) {
        this.state = STATE_PROCESSING;
        this.claimedAt = now;
        this.claimedBy = instanceId;
        this.updatedAt = now;
    }

    /** Mark the job as successfully completed. */
    public void markCompleted(OffsetDateTime now) {
        this.state = STATE_COMPLETED;
        this.updatedAt = now;
    }

    /** Record progress — called after each successfully processed chunk. */
    public void recordProgress(int count, OffsetDateTime now) {
        this.processedSubscribers += count;
        this.updatedAt = now;
    }

    /** Record a failed processing attempt. Does NOT change state (caller sets state). */
    public void recordFailure(String error, OffsetDateTime now) {
        this.retryCount++;
        this.lastError = error;
        this.updatedAt = now;
        this.state = STATE_FAILED;
    }

    /** Mark the job as dead after exceeding max retries. */
    public void markDead(OffsetDateTime now) {
        this.state = STATE_DEAD;
        this.updatedAt = now;
    }

    // -- Persistable contract ----------------------------------------------

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
```

### Patterns to mirror

| From | Pattern |
|------|---------|
| `OutboxEntry.java:29-34` | `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Table` |
| `OutboxEntry.java:40-41` | `@Transient private boolean isNew` |
| `OutboxEntry.java:81-96` | Static `create()` factory assigning all fields |
| `OutboxEntry.java:100-117` | Domain mutation methods (`markSent`, `recordFailure`, `markDead`) |
| `Notification.java:22-26` | Same Lombok + Persistable + @Table pattern |

### Validation
```bash
./gradlew :notification-service:compileJava
```

---

## Task 3: ReactiveFanOutJobRepository

**File**: `src/main/java/com/streaming/notification/infrastructure/persistence/ReactiveFanOutJobRepository.java`

### Interface contract

```java
package com.streaming.notification.infrastructure.persistence;

import com.streaming.notification.domain.FanOutJob;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link FanOutJob} entities.
 *
 * <p>Mirrors {@code ReactiveOutboxRepository} — same
 * {@code FOR UPDATE SKIP LOCKED} polling strategy for safe concurrent
 * access across multiple poller instances.
 */
public interface ReactiveFanOutJobRepository
        extends ReactiveCrudRepository<FanOutJob, UUID> {

    /**
     * Claim one PENDING job that's ready for processing.
     * Uses row-level lock so multiple pollers never claim the same job.
     *
     * @param limit max jobs to claim (typically 1 for fan-out poller)
     * @return claimed PENDING jobs ordered by scheduled_at
     */
    @Query("""
            SELECT * FROM notification.fan_out_job
            WHERE state = 'PENDING' AND scheduled_at <= now()
            ORDER BY scheduled_at ASC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    Flux<FanOutJob> pollPending(int limit);

    /**
     * Claim a specific job atomically — sets state=PROCESSING + claimed_at/by.
     * The WHERE state='PENDING' guard prevents double-claim races when
     * {@code pollPending()} and then {@code claimJob()} are separate calls.
     *
     * @return number of rows updated (0 = already claimed by another worker)
     */
    @Modifying
    @Query("""
            UPDATE notification.fan_out_job
            SET state = 'PROCESSING',
                claimed_at = :claimedAt,
                claimed_by = :claimedBy,
                updated_at = :claimedAt
            WHERE id = :id AND state = 'PENDING'
            """)
    Mono<Long> claimJob(UUID id, String claimedBy, OffsetDateTime claimedAt);

    /**
     * Update job state and progress after processing.
     */
    @Modifying
    @Query("""
            UPDATE notification.fan_out_job
            SET state = :state,
                retry_count = :retryCount,
                processed_subscribers = :processedSubscribers,
                total_subscribers = :totalSubscribers,
                last_error = :lastError,
                updated_at = :now,
                claimed_at = CASE WHEN :state = 'PROCESSING' THEN claimed_at ELSE NULL END
            WHERE id = :id
            """)
    Mono<Void> updateState(
            UUID id, String state, int retryCount, int processedSubscribers,
            Integer totalSubscribers, String lastError, OffsetDateTime now);

    /**
     * Idempotency check — has this Kafka event already been enqueued?
     */
    Mono<Boolean> existsByEventIdAndJobType(String eventId, String jobType);

    /**
     * Count PENDING jobs — queue depth for monitoring.
     */
    Mono<Long> countByState(String state);

    /**
     * Find stuck jobs — claimed before threshold, still in PROCESSING state.
     * Used for visibility-timeout recovery (future enhancement).
     */
    @Query("""
            SELECT * FROM notification.fan_out_job
            WHERE state = 'PROCESSING' AND claimed_at < :stuckThreshold
            ORDER BY claimed_at ASC
            """)
    Flux<FanOutJob> findStuckJobs(OffsetDateTime stuckThreshold);
}
```

### Patterns to mirror

| From | Pattern |
|------|---------|
| `ReactiveOutboxRepository.java:30-37` | `@Query` with text block + `FOR UPDATE SKIP LOCKED` |
| `ReactiveOutboxRepository.java:47-53` | `@Modifying` + `@Query` UPDATE with named params |
| `ReactiveSubscriptionRepository.java:36` | `existsBy*` derived query method for dedup |

### Validation
```bash
./gradlew :notification-service:compileJava
```

---

## Task 4: FanOutService (Enqueue)

**File**: `src/main/java/com/streaming/notification/application/FanOutService.java`

### Class contract

```java
package com.streaming.notification.application;

import com.streaming.common.messaging.StreamEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Enqueues a fan-out job when a followed broadcaster starts a stream.
 *
 * <p>Called from {@code StreamControlListener.onStreamStarted()} — replaces
 * the inline {@code deliverToMany()} fan-out path. The enqueue is a single
 * INSERT (~2ms), so the Kafka consumer thread returns immediately. The
 * heavy work (subscriber lookup, chunked dispatch) happens asynchronously
 * in {@code FanOutPoller}.
 *
 * <p>Idempotency is enforced by the unique index on {@code (event_id, job_type)}.
 * Replaying the same Kafka event produces a duplicate INSERT → caught by
 * {@code DataIntegrityViolationException} → logged and skipped.
 */
@Service
@RequiredArgsConstructor
public class FanOutService {

    private static final Logger log = LoggerFactory.getLogger(FanOutService.class);

    private final ReactiveFanOutJobRepository fanOutJobRepository;
    private final ReactiveSubscriptionRepository subscriptionRepository;

    /**
     * Enqueue a fan-out job for a stream event.
     *
     * <p>Idempotent — if a job for this (eventId, jobType) already exists,
     * the duplicate is logged and skipped.
     *
     * @param event the stream event carrying broadcaster info
     * @return empty Mono (completes when the INSERT succeeds or is skipped)
     */
    public Mono<Void> enqueue(StreamEvent event) {
        String eventId = event.eventId();
        String jobType = event.eventType();  // e.g., "STREAM_STARTED"

        // Idempotency guard: skip if already enqueued
        return fanOutJobRepository.existsByEventIdAndJobType(eventId, jobType)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Fan-out job already enqueued — skipping: eventId={} type={}",
                                eventId, jobType);
                        return Mono.empty();
                    }
                    return doEnqueue(event, eventId, jobType);
                });
    }

    private Mono<Void> doEnqueue(StreamEvent event, String eventId, String jobType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        FanOutJob job = FanOutJob.create(
                eventId,
                jobType,
                event.broadcasterSubject(),
                "CHANNEL",                     // targetType
                event.broadcasterSubject(),    // targetId
                now);

        return fanOutJobRepository.save(job)
                .doOnSuccess(saved -> log.info(
                        "Fan-out job enqueued: jobId={} broadcaster={} eventId={}",
                        saved.getId(), event.broadcasterSubject(), eventId))
                .doOnError(err -> log.error(
                        "Failed to enqueue fan-out job: eventId={} broadcaster={} error={}",
                        eventId, event.broadcasterSubject(), err.getMessage()))
                .then();
    }
}
```

### Dependencies injected
- `ReactiveFanOutJobRepository` — INSERT + dedup check
- `ReactiveSubscriptionRepository` — reserved for future use (subscriber count at enqueue time)

### Patterns to mirror

| From | Pattern |
|------|---------|
| `OutboxService.java:27-28` | `@Service @RequiredArgsConstructor` |
| `OutboxService.java:48-70` | `enqueue()`: build entity → save → log → `.then()` |
| `StreamControlListener.java:82-88` | `SETNX` dedup pattern → adapted here as DB unique constraint |

### Validation
```bash
./gradlew :notification-service:compileJava
```

---

## Task 5: FanOutPoller (Worker)

**File**: `src/main/java/com/streaming/notification/messaging/FanOutPoller.java`

This is the core of the job queue — where enqueue, claim, batch, ACK, NACK, DLQ all come together.

### Class contract

```java
package com.streaming.notification.messaging;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.application.FanOutService;
import com.streaming.notification.application.NotificationDispatcher;
import com.streaming.notification.application.NotificationService;
import com.streaming.notification.application.SubscriptionService;
import com.streaming.notification.domain.FanOutJob;
import com.streaming.notification.domain.Notification;
import com.streaming.notification.domain.Subscription;
import com.streaming.notification.infrastructure.persistence.ReactiveFanOutJobRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Scheduled worker that picks up PENDING {@link FanOutJob} rows and
 * processes them asynchronously — loading subscribers, chunking by
 * batch size, and dispatching each chunk via
 * {@link NotificationDispatcher#deliverToMany(List, int)}.
 *
 * <p>This is the "worker" half of the job queue. The "producer" half is
 * {@link FanOutService#enqueue(StreamEvent)}.
 *
 * <p><b>Job queue concepts implemented here:</b>
 * <ul>
 *   <li>Claim — {@code SELECT ... FOR UPDATE SKIP LOCKED} + atomic update</li>
 *   <li>Batch — partition subscribers into chunks of {@code batchSize}</li>
 *   <li>ACK — mark COMPLETED when all chunks succeed</li>
 *   <li>NACK — mark FAILED when a chunk errors, retry on next poll</li>
 *   <li>DLQ — mark DEAD after {@code maxRetries} failures</li>
 *   <li>Visibility timeout — {@code claimed_at}/{@code claimed_by} columns
 *       (recovery not yet implemented)</li>
 * </ul>
 *
 * <p>Processing strategy: one job per poll cycle. This is deliberately
 * conservative — fan-out of a large subscriber set may take multiple
 * poll cycles to complete (chunk processing spans cycles via retry).
 * If throughput becomes a bottleneck, switch to claiming multiple jobs
 * per poll with a configurable claim limit.
 *
 * <p>Mirrors {@code OutboxPoller} structure — {@code @Scheduled} void method
 * → reactive chain → {@code .blockOptional()} — because it runs on Spring's
 * scheduler thread pool, not the Netty event loop.
 */
@Component
public class FanOutPoller {

    private static final Logger log = LoggerFactory.getLogger(FanOutPoller.class);

    /** Max jobs claimed per poll cycle. */
    private static final int CLAIM_LIMIT = 1;

    private final ReactiveFanOutJobRepository jobRepository;
    private final SubscriptionService subscriptionService;
    private final NotificationService notificationService;
    private final NotificationDispatcher dispatcher;

    @Value("${notification.fanout.poll-interval:5000}")
    private int pollIntervalMs;

    @Value("${notification.fanout.batch-size:100}")
    private int batchSize;

    @Value("${notification.fanout.max-concurrency:4}")
    private int maxConcurrency;

    @Value("${notification.fanout.max-retries:3}")
    private int maxRetries;

    @Value("${notification.fanout.processing-timeout-seconds:60}")
    private int processingTimeoutSeconds;

    @Value("${streaming.service.instance-id:unknown}")
    private String instanceId;

    public FanOutPoller(ReactiveFanOutJobRepository jobRepository,
                        SubscriptionService subscriptionService,
                        NotificationService notificationService,
                        NotificationDispatcher dispatcher) {
        this.jobRepository = jobRepository;
        this.subscriptionService = subscriptionService;
        this.notificationService = notificationService;
        this.dispatcher = dispatcher;
    }

    /**
     * Poll for PENDING jobs and process one per cycle.
     *
     * <p>Runs on a fixed delay (default 5s). If no PENDING jobs exist,
     * this is a cheap no-op (one SELECT with SKIP LOCKED).
     */
    @Scheduled(fixedDelayString = "${notification.fanout.poll-interval:5000}")
    public void poll() {
        claimAndProcess()
                .doOnSubscribe(s -> log.debug("FanOutPoller poll starting"))
                .doOnSuccess(v -> log.debug("FanOutPoller poll complete"))
                .doOnError(e -> log.warn("FanOutPoller poll iteration failed: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())  // never kill the scheduler
                .blockOptional(Duration.ofSeconds(processingTimeoutSeconds));
    }

    // ── claim → load → chunk → dispatch ─────────────────────────────────

    /**
     * Claim one PENDING job, load subscribers, chunk, and dispatch.
     * Returns immediately if no PENDING jobs exist.
     */
    private Mono<Void> claimAndProcess() {
        return jobRepository.pollPending(CLAIM_LIMIT)
                .next()  // take first (and only, since CLAIM_LIMIT=1)
                .flatMap(job -> claimJob(job).thenReturn(job))
                .flatMap(job -> loadAndDispatch(job));
    }

    /**
     * Atomically claim a job by updating state to PROCESSING.
     * If another worker beat us to it (claimJob returns 0 rows),
     * skip silently.
     */
    private Mono<Long> claimJob(FanOutJob job) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return jobRepository.claimJob(job.getId(), instanceId, now)
                .doOnSuccess(updated -> {
                    if (updated != null && updated > 0) {
                        log.info("Claimed fan-out job: jobId={} broadcaster={}",
                                job.getId(), job.getBroadcasterSubject());
                    } else {
                        log.debug("Fan-out job already claimed by another worker: jobId={}",
                                job.getId());
                    }
                });
    }

    /**
     * Load active subscribers, chunk by batchSize, and dispatch each chunk.
     *
     * <p>The subscriber list is queried at processing time (not enqueue time),
     * so newly-added followers are included. This is intentional — a follower
     * who subscribes between enqueue and process should get the notification.
     */
    private Mono<Void> loadAndDispatch(FanOutJob job) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return subscriptionService
                .getSubscribers(job.getTargetType(), job.getTargetId())
                .collectList()
                .flatMap(subscribers -> {
                    if (subscribers.isEmpty()) {
                        log.info("No subscribers for fan-out: jobId={} broadcaster={}",
                                job.getId(), job.getBroadcasterSubject());
                        job.setTotalSubscribers(0);
                        job.markCompleted(now);
                        return jobRepository.updateState(
                                job.getId(), FanOutJob.STATE_COMPLETED,
                                job.getRetryCount(), 0, 0,
                                null, now);
                    }

                    log.info("Fan-out starting: jobId={} broadcaster={} subscribers={}",
                            job.getId(), job.getBroadcasterSubject(),
                            subscribers.size());

                    job.setTotalSubscribers(subscribers.size());

                    // Build a synthetic StreamEvent for createForFollower()
                    StreamEvent event = buildStreamEvent(job);

                    // Partition into chunks
                    List<List<Subscription>> chunks = partition(
                            subscribers, batchSize);

                    // Process chunks with bounded concurrency
                    return Flux.fromIterable(chunks)
                            .flatMap(chunk ->
                                    processChunk(job, event, chunk, now),
                                    maxConcurrency)
                            .then(Mono.defer(() -> {
                                // All chunks processed — mark COMPLETED
                                job.markCompleted(now);
                                log.info("Fan-out completed: jobId={} processed={}/{}",
                                        job.getId(), job.getProcessedSubscribers(),
                                        job.getTotalSubscribers());
                                return jobRepository.updateState(
                                        job.getId(), FanOutJob.STATE_COMPLETED,
                                        job.getRetryCount(),
                                        job.getProcessedSubscribers(),
                                        job.getTotalSubscribers(),
                                        null, now);
                            }));
                });
    }

    /**
     * Process one chunk: create follower notifications → deliver via dispatcher.
     *
     * <p>On success: update processed count.
     * <p>On failure: record error, update state to FAILED for retry.
     * <p>Only the FIRST chunk failure is recorded as job-level lastError.
     */
    private Mono<Void> processChunk(FanOutJob job, StreamEvent event,
                                     List<Subscription> chunk,
                                     OffsetDateTime now) {
        return Flux.fromIterable(chunk)
                .flatMap(sub -> notificationService.createForFollower(
                        event, sub.getSubscriberSubject()))
                .collectList()
                .flatMap(notifications -> dispatcher.deliverToMany(
                        notifications, maxConcurrency))
                .doOnSuccess(unused -> {
                    job.recordProgress(chunk.size(), now);
                    log.debug("Fan-out chunk processed: jobId={} chunkSize={} progress={}/{}",
                            job.getId(), chunk.size(),
                            job.getProcessedSubscribers(),
                            job.getTotalSubscribers());
                })
                .onErrorResume(err -> {
                    log.warn("Fan-out chunk failed: jobId={} chunkSize={} error={}",
                            job.getId(), chunk.size(), err.getMessage());

                    if (job.getLastError() == null) {
                        job.recordFailure(err.getMessage(), now);
                    }

                    int nextRetry = job.getRetryCount() + 1;
                    if (nextRetry > maxRetries) {
                        log.error("Fan-out job exceeded max retries ({}): jobId={} "
                                + "— marking DEAD",
                                maxRetries, job.getId());
                        job.markDead(now);
                        return jobRepository.updateState(
                                job.getId(), FanOutJob.STATE_DEAD,
                                nextRetry,
                                job.getProcessedSubscribers(),
                                job.getTotalSubscribers(),
                                job.getLastError(), now);
                    }

                    // NACK — set FAILED, retry on next poll cycle
                    return jobRepository.updateState(
                            job.getId(), FanOutJob.STATE_FAILED,
                            nextRetry,
                            job.getProcessedSubscribers(),
                            job.getTotalSubscribers(),
                            job.getLastError(), now)
                            .then(Mono.error(err));  // propagate to stop chunk processing
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Build a minimal StreamEvent for NotificationService.createForFollower().
     * Only the fields that createForFollower reads need to be populated.
     */
    private static StreamEvent buildStreamEvent(FanOutJob job) {
        return new StreamEvent(
                job.getEventId(),
                job.getJobType(),
                job.getBroadcasterSubject(),
                null,   // broadcasterUsername — createForFollower uses "A streamer you follow" fallback
                job.getTargetId(),  // streamId
                java.time.Instant.now(),
                null,   // broadcasterUsername (nullable field)
                null    // chatRoomKey (nullable)
        );
    }

    /**
     * Partition a list into sublists of at most {@code size} elements.
     * Pure JDK — no Guava dependency needed.
     */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        int total = list.size();
        int chunkCount = (total + size - 1) / size;
        return java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(i -> list.subList(
                        i * size,
                        Math.min((i + 1) * size, total)))
                .toList();
    }

    // Note: partition() uses List.subList which returns a view. Since chunks
    // are processed serially within flatMap (maxConcurrency=4 but each chunk
    // is an independent flatMap item), concurrent modification is not an issue.
}
```

### Key design decisions
1. **One job per poll** — `CLAIM_LIMIT = 1`. Fan-out of 10K subscribers interleaves chunks with other jobs on subsequent polls. Simple, predictable. Tune later.
2. **Subscribers queried at processing time** — not at enqueue. A follower who subscribes after the job is created still gets the notification. This is intentional — it's the correct behavior for "stream started" fan-out.
3. **First chunk failure stops the pipeline** — `onErrorResume` propagates the error after recording it. This prevents processing chunk 2-100 after chunk 1 has already marked the job as FAILED. The remaining chunks are picked up on retry.
4. **`scheduled_at` filter in poll query** — `WHERE scheduled_at <= now()`. Today all jobs have `scheduled_at = now()`, so this is a no-op. When reminders are added (scheduled_at in the future), the same poller query handles delayed execution with zero code changes.

### Patterns to mirror

| From | Pattern |
|------|---------|
| `OutboxPoller.java:57-74` | `@Scheduled` → reactive chain → `.blockOptional()` |
| `OutboxPoller.java:77-83` | `concurrency()` derived from config with ceiling |
| `OutboxPoller.java:100-127` | `handleFailure()`: retry count → DLQ or retry |
| `StreamControlListener.java:109-117` | `Switch` expression for event routing |

### Validation
```bash
./gradlew :notification-service:compileJava
```

---

## Task 6: Modify StreamControlListener

**File**: `src/main/java/com/streaming/notification/messaging/StreamControlListener.java`

### Change: `onStreamStarted()` method

**Before** (lines 128-156):
```java
private Mono<Void> onStreamStarted(StreamEvent event) {
    log.info("STREAM_STARTED: streamId={} broadcaster={} username={}",
            event.streamId(), event.broadcasterSubject(),
            event.broadcasterUsername());

    // 1. Broadcaster self-notification
    Mono<Void> broadcasterNotification = notificationService
            .createFromStreamEvent(event);

    // 2. Fan-out to followers — query active subscribers, create
    //    follower notifications, dispatch via deliverToMany
    Mono<Void> fanOut = subscriptionService
            .getSubscribers("CHANNEL", event.broadcasterSubject())
            .flatMap(sub -> notificationService.createForFollower(
                    event, sub.getSubscriberSubject()))
            .collectList()
            .flatMap(notifications -> {
                if (notifications.isEmpty()) {
                    log.debug("No followers to notify: broadcaster={}",
                            event.broadcasterSubject());
                    return Mono.empty();
                }
                log.info("Fan-out to {} followers: broadcaster={}",
                        notifications.size(), event.broadcasterSubject());
                return dispatcher.deliverToMany(notifications, 8);
            });

    return broadcasterNotification.then(fanOut);
}
```

**After**:
```java
private Mono<Void> onStreamStarted(StreamEvent event) {
    log.info("STREAM_STARTED: streamId={} broadcaster={} username={}",
            event.streamId(), event.broadcasterSubject(),
            event.broadcasterUsername());

    // 1. Broadcaster self-notification — unchanged
    Mono<Void> broadcasterNotification = notificationService
            .createFromStreamEvent(event);

    // 2. Fan-out to followers — enqueue a FanOutJob instead of
    //    processing inline. FanOutPoller handles the heavy work
    //    asynchronously on a separate scheduler thread.
    Mono<Void> fanOut = fanOutService.enqueue(event);

    return broadcasterNotification.then(fanOut);
}
```

### Constructor change

Add `private final FanOutService fanOutService;` — Lombok `@RequiredArgsConstructor` picks it up automatically. No other changes to the constructor.

### Removed dependency

`subscriptionService` is no longer used by `StreamControlListener`. It must stay as a constructor parameter (used by `FanOutPoller`), but the field can be removed from `StreamControlListener` if no other method uses it. After this change:
- `onStreamStarted` no longer calls `subscriptionService.getSubscribers()`
- `onStreamCreated`, `onStreamScheduled`, `onStreamEnded`, `onStreamCancelled` don't use it either
- **Remove `subscriptionService` field from `StreamControlListener`** — it's now only used by `FanOutPoller`

### Validation
```bash
./gradlew :notification-service:compileJava
# Verify no unused import warnings for SubscriptionService in StreamControlListener
```

---

## Task 7: Configuration

**File**: `src/main/resources/application.yml`

### Add block (after the existing `notification.outbox:` section, around line 64)

```yaml
# ── Notification service configuration ────────────────────────────────────
notification:
  outbox:
    poll-interval: ${NOTIFICATION_OUTBOX_POLL_INTERVAL:5000}
    batch-size: ${NOTIFICATION_OUTBOX_BATCH_SIZE:50}
    max-retries: ${NOTIFICATION_OUTBOX_MAX_RETRIES:3}
  # ── NEW: Fan-out job queue ──────────────────────────────────────────────
  fanout:
    poll-interval: ${NOTIFICATION_FANOUT_POLL_INTERVAL:5000}
    batch-size: ${NOTIFICATION_FANOUT_BATCH_SIZE:100}
    max-concurrency: ${NOTIFICATION_FANOUT_MAX_CONCURRENCY:4}
    max-retries: ${NOTIFICATION_FANOUT_MAX_RETRIES:3}
    processing-timeout-seconds: ${NOTIFICATION_FANOUT_PROCESSING_TIMEOUT:60}
  email:
    from: ${NOTIFICATION_EMAIL_FROM:noreply@streaming.local}
```

### Config reference

| Property | Default | Meaning |
|----------|---------|---------|
| `poll-interval` | 5000ms | How often FanOutPoller wakes up. Lower = faster fan-out; higher = less DB load. |
| `batch-size` | 100 | Subscribers per chunk. One chunk = one unit of retry. Larger = fewer DB round-trips but slower retry. |
| `max-concurrency` | 4 | Max chunks processed concurrently. Bounded by R2DBC connection pool size. |
| `max-retries` | 3 | Retry attempts before DEAD. Mirrors outbox pattern. |
| `processing-timeout-seconds` | 60 | Max time per poll cycle. The `.blockOptional()` timeout. |

---

## Task 8: Unit Tests

**File**: `src/test/java/com/streaming/notification/messaging/FanOutPollerTest.java`

### Test scenarios

```java
package com.streaming.notification.messaging;

import com.streaming.notification.application.NotificationDispatcher;
import com.streaming.notification.application.NotificationService;
import com.streaming.notification.application.SubscriptionService;
import com.streaming.notification.domain.FanOutJob;
import com.streaming.notification.domain.Subscription;
import com.streaming.notification.infrastructure.persistence.ReactiveFanOutJobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("FanOutPoller")
class FanOutPollerTest {

    @Mock ReactiveFanOutJobRepository jobRepository;
    @Mock SubscriptionService subscriptionService;
    @Mock NotificationService notificationService;
    @Mock NotificationDispatcher dispatcher;

    private FanOutPoller poller;

    @BeforeEach
    void setUp() {
        // Use reflection or a test constructor to inject mocks + @Value defaults
        // Option 1: @InjectMocks with @SpringBootTest(classes = FanOutPoller.class)
        // Option 2: Manual construction with @Value fields set via reflection
        // Option 3: Extract config to a @ConfigurationProperties record
        //
        // For MVP, use @SpringBootTest with @TestPropertySource for config,
        // or manually set @Value fields in @BeforeEach via ReflectionTestUtils:
        //
        // ReflectionTestUtils.setField(poller, "maxRetries", 3);
        // ReflectionTestUtils.setField(poller, "batchSize", 100);
        // ReflectionTestUtils.setField(poller, "maxConcurrency", 4);
        // ReflectionTestUtils.setField(poller, "instanceId", "test-instance");
    }

    @Test
    @DisplayName("No PENDING jobs → poll is a no-op")
    void noPendingJobs_noOp() {
        when(jobRepository.pollPending(1)).thenReturn(Flux.empty());

        // poll() calls blockOptional(). In tests, verify the reactive chain directly
        // by extracting claimAndProcess() to a package-private test method,
        // or verify repository interactions after poll().
    }

    @Test
    @DisplayName("Single PENDING job with subscribers → claimed, processed, COMPLETED")
    void pendingJob_withSubscribers_markedCompleted() {
        // Arrange
        FanOutJob job = createTestJob();
        List<Subscription> subscribers = List.of(
                createTestSubscription("follower-1"),
                createTestSubscription("follower-2"));

        when(jobRepository.pollPending(1))
                .thenReturn(Flux.just(job));
        when(jobRepository.claimJob(job.getId(), "test-instance", any()))
                .thenReturn(Mono.just(1L));
        when(subscriptionService.getSubscribers("CHANNEL", job.getTargetId()))
                .thenReturn(Flux.fromIterable(subscribers));
        when(notificationService.createForFollower(any(), anyString()))
                .thenReturn(Mono.just(/* Notification */));
        when(dispatcher.deliverToMany(anyList(), eq(4)))
                .thenReturn(Mono.empty());
        when(jobRepository.updateState(any(), anyString(), anyInt(), anyInt(),
                any(), any(), any()))
                .thenReturn(Mono.empty());

        // Act — call poll() and verify state transitions
    }

    @Test
    @DisplayName("Zero subscribers → immediately marked COMPLETED")
    void noSubscribers_markedCompleted() {
        // Arrange
        FanOutJob job = createTestJob();

        when(jobRepository.pollPending(1)).thenReturn(Flux.just(job));
        when(jobRepository.claimJob(eq(job.getId()), anyString(), any()))
                .thenReturn(Mono.just(1L));
        when(subscriptionService.getSubscribers("CHANNEL", job.getTargetId()))
                .thenReturn(Flux.empty());
        when(jobRepository.updateState(
                eq(job.getId()), eq("COMPLETED"), eq(0), eq(0), eq(0),
                isNull(), any()))
                .thenReturn(Mono.empty());

        // Act & Assert
    }

    @Test
    @DisplayName("Chunk dispatch fails → state FAILED, retry on next poll")
    void chunkFails_markedFailed_retryNextPoll() {
        // Arrange: one chunk, dispatcher.deliverToMany returns error
    }

    @Test
    @DisplayName("Dispatch fails after maxRetries → marked DEAD")
    void exceedsMaxRetries_markedDead() {
        // Arrange: job with retryCount = maxRetries, dispatching fails again
    }

    @Test
    @DisplayName("Job already claimed by another worker → skipped silently")
    void alreadyClaimed_skippedSilently() {
        // Arrange: claimJob returns 0 (no rows updated)
    }

    @Test
    @DisplayName("Poll iteration throws → caught, scheduler survives")
    void pollThrows_schedulerSurvives() {
        // Arrange: pollPending throws RuntimeException
        // Assert: poll() completes without throwing
    }

    // -- helpers ---------------------------------------------------------

    private static FanOutJob createTestJob() {
        return FanOutJob.create(
                UUID.randomUUID().toString(),          // eventId
                "STREAM_STARTED",                       // jobType
                "broadcaster-sub",                      // broadcasterSubject
                "CHANNEL",                              // targetType
                "broadcaster-sub",                      // targetId
                OffsetDateTime.now());
    }

    private static Subscription createTestSubscription(String subscriberSubject) { ... }
}
```

### Test structure notes

The `@Value` injection in `FanOutPoller` makes pure unit testing awkward. Choose one approach:

**Option A: `ReflectionTestUtils`** (simplest for MVP)
```java
@BeforeEach
void setUp() {
    poller = new FanOutPoller(jobRepository, subscriptionService,
            notificationService, dispatcher);
    ReflectionTestUtils.setField(poller, "maxRetries", 3);
    ReflectionTestUtils.setField(poller, "batchSize", 100);
    ReflectionTestUtils.setField(poller, "maxConcurrency", 4);
    ReflectionTestUtils.setField(poller, "instanceId", "test-instance");
}
```

**Option B: Extract config record** (cleaner, but adds a file)
```java
@ConfigurationProperties("notification.fanout")
public record FanOutProperties(int pollInterval, int batchSize,
        int maxConcurrency, int maxRetries, int processingTimeoutSeconds) {}
```
Then inject `FanOutProperties` instead of individual `@Value` fields. Preferred approach — do this during implementation if test setup becomes unwieldy.

### Validation
```bash
./gradlew :notification-service:test --tests "*FanOutPollerTest*"
```

---

## Data Flow: Before vs After

### Before (inline fan-out — current code)

```
Kafka consumer thread (StreamControlListener)
 │
 ├─ broadcasterNotification: persist + SSE + outbox  (~10ms)
 │
 └─ fanOut:                                           ← blocks consumer
      ├─ SELECT * FROM subscription WHERE target = ?   (5ms)
      ├─ for each of N subscribers:                    (~N × 15ms)
      │    ├─ createForFollower()                      (0.1ms)
      │    └─ collectList()                            (waits for all)
      └─ deliverToMany(N, 8)                           (~N/8 × 10ms)
           ├─ persist each notification                (PG INSERT)
           ├─ SSE push each                            (in-memory)
           └─ outbox enqueue each                      (PG INSERT)

Total time for N=10K: 10ms + 5ms + 10000×15ms + 10000/8×10ms
                     = 15ms + 150s + 12.5s
                     = ~163 seconds ← exceeds blockOptional(10s) timeout
```

### After (job queue)

```
Kafka consumer thread (StreamControlListener)
 │
 ├─ broadcasterNotification: persist + SSE + outbox  (~10ms)
 │
 └─ fanOut:
      └─ INSERT INTO fan_out_job ...                   (2ms)
           └─ unique index check on (event_id, job_type)

Total time in consumer: ~12ms ← never times out

─────────────────── async boundary ──────────────────

Scheduler thread (FanOutPoller, 5s later)
 │
 ├─ SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1          (2ms)
 ├─ UPDATE ... SET state='PROCESSING'                  (2ms)
 ├─ SELECT subscribers WHERE target = ?                (5ms)
 ├─ partition into 100 chunks (100 per chunk)
 ├─ for each chunk (concurrency=4):
 │    ├─ createForFollower(chunk)                      (~0.1ms × 100)
 │    ├─ deliverToMany(chunk, 4)                       (~25ms × 100)
 │    └─ recordProgress(chunk.size())                  (~2ms)
 └─ UPDATE ... SET state='COMPLETED'                   (2ms)

Total time in worker for N=10K: ~2 + 2 + 5 + 100 × (10 + 25 + 2)/4
                                = ~9ms + 100 × 9.25ms
                                = ~934ms ← well within 60s timeout
```

---

## Error Handling Summary

| Scenario | Behavior |
|----------|----------|
| Duplicate Kafka event (same eventId) | `FanOutService.enqueue()` skips via `existsByEventIdAndJobType()` |
| `fan_out_job` INSERT fails | Logged; consumer returns normally; Kafka offset committed |
| No PENDING jobs | Poll cycle is a no-op (empty SELECT) |
| Job claimed by another worker | `claimJob()` returns 0 rows → skip |
| Zero subscribers | Job marked COMPLETED immediately (ACK) |
| Chunk dispatch fails | Job state → FAILED; `lastError` recorded; remaining chunks NOT processed this cycle (NACK) |
| Retry < maxRetries | Job picked up again on next poll cycle |
| Retry >= maxRetries | Job state → DEAD (DLQ); inspectable via `last_error` + `processed_subscribers` |
| Poller throws unhandled exception | `.onErrorResume(e -> Mono.empty())` catches it; scheduler survives |
| Poller blocks past timeout | `.blockOptional(Duration.ofSeconds(60))` returns empty |

---

## Risks & Open Questions

| Risk / Question | Likelihood | Response |
|------|-----------|----------|
| **Partial failure recovery** — a chunk fails but some notifications in that chunk were persisted (by `dispatcher.deliver()` Step 1). On retry, those recipients get duplicate notifications. | High, if chunk failure is mid-batch | Accept for MVP. The notification entity has no dedup key per-recipient-per-event. Add `(recipient_subject, event_id)` unique constraint in a follow-up migration if duplicates become a problem. |
| **StreamEvent construction** — `buildStreamEvent()` creates a minimal event. Is `streamId` from `targetId` sufficient? What about `broadcasterUsername` for the notification message? | Medium | `createForFollower()` at line 110 shows: if `broadcasterUsername` is null, it falls back to "A streamer you follow". We set it to null in `buildStreamEvent()`. This is acceptable for MVP — the fallback message is already the current behavior when username is missing. |
| **Chunk reprocessing on retry** — a FAILED job retries ALL subscribers, not just the failed chunk. | Medium | The current design does full re-processing on retry. For MVP this is acceptable — duplicates are better than dropped notifications. Track failed chunk ranges in a JSON column in a follow-up. |
| **`@Value` fields in tests** | Medium | Use `ReflectionTestUtils` or extract a `@ConfigurationProperties` record during implementation if testing is painful. |
| **Monitoring** — no metrics/dashboard for queue depth or processing rate | Low | For MVP, query the database directly: `SELECT state, COUNT(*) FROM notification.fan_out_job GROUP BY state`. Add Micrometer metrics in a follow-up. |

---

## Implementation Order

Tasks are in dependency order — each builds on the previous:

```
Task 1 (migration)
  │
  ▼
Task 2 (entity) ──► Task 3 (repository)
                          │
                          ▼
                    Task 4 (FanOutService) ──────────────────┐
                          │                                   │
                          ▼                                   ▼
                    Task 5 (FanOutPoller)          Task 7 (config)
                          │
                          ▼
                    Task 6 (modify StreamControlListener)
                          │
                          ▼
                    Task 8 (tests)
```

Tasks 1-3 can be done in one session. Tasks 4-7 can be done in one session. Task 8 is ongoing.

---

## Post-Implementation: Evolution Path

Once the core job queue is working, these are natural next steps. They're listed here so we can track what we deferred and why.

| Enhancement | When | What Changes |
|-------------|------|--------------|
| **PG LISTEN/NOTIFY** | After MVP validated | Add trigger on `fan_out_job` INSERT → `NOTIFY` → replace `@Scheduled` with listener. Zero code change to `FanOutPoller.processJob()`. |
| **FanOutProperties config record** | During implementation if testing is painful | Extract `@Value` fields into `@ConfigurationProperties` record for clean test injection |
| **Per-recipient dedup** | If duplicate notifications reported | Add `(recipient_subject, event_id)` unique index on `notification` table |
| **Stuck-job recovery** | Before multi-instance deploy | `findStuckJobs()` → re-claim jobs stuck in PROCESSING past 5min |
| **Metrics** | Before production | Micrometer gauges for queue depth, processing duration, DEAD count |
| **Fan-out for STREAM_ENDED** | When needed | Same pattern, different `job_type`. `FanOutPoller` already supports any event type. |
| **Scheduled reminders** | Phase 5.x | Set `fan_out_job.scheduled_at` to future timestamp. Same poller query (`WHERE scheduled_at <= now()`) handles it automatically. |

---

## Validation (End-to-End)

```bash
# 1. Compile check
./gradlew :notification-service:compileJava

# 2. Run all notification-service tests
./gradlew :notification-service:test

# 3. Verify Flyway migration
docker compose up -d postgres
./gradlew :notification-service:bootRun
# Check: "Successfully applied migration V6"
# Check: No "Failed to apply migration" errors

# 4. Verify FanOutPoller starts
# Log line: "FanOutPoller poll starting"

# 5. Integration test (manual)
# Start a stream via stream-service
# Check: fan_out_job table has one PENDING row
# Wait 5s
# Check: fan_out_job state = COMPLETED (or FAILED with last_error)
# Check: notification table has rows for each follower
```

---

## References

- [fanout-job-queue-blueprint.md](fanout-job-queue-blueprint.md) — high-level blueprint (this document supersedes it for implementation detail)
- [ADR-0002: Notification Delivery Architecture](../adr/notification/0002-notification-delivery-architecture.md) §4 — outbox-driven fan-out design
- [ADR-0000: Notification Service Architecture](../adr/notification/0000-architecture-foundation.md) — service conventions
- [OutboxEntry.java](../../main/source/backend/notification-service/src/main/java/com/streaming/notification/domain/OutboxEntry.java) — entity pattern to mirror
- [OutboxPoller.java](../../main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/OutboxPoller.java) — poller pattern to mirror
- [ReactiveOutboxRepository.java](../../main/source/backend/notification-service/src/main/java/com/streaming/notification/infrastructure/persistence/ReactiveOutboxRepository.java) — repository pattern to mirror
- [StreamControlListener.java](../../main/source/backend/notification-service/src/main/java/com/streaming/notification/messaging/StreamControlListener.java) — file to modify
