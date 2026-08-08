package com.streaming.notification.messaging;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.application.NotificationDispatcher;
import com.streaming.notification.application.NotificationService;
import com.streaming.notification.application.SubscriptionService;
import com.streaming.notification.domain.FanOutJob;
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

/**
 * Scheduled worker that picks up PENDING {@link FanOutJob} rows and
 * processes them asynchronously — loading subscribers, chunking by
 * batch size, and dispatching each chunk via
 * {@link NotificationDispatcher#deliverToMany(List, int)}.
 *
 * <p>This is the "worker" half of the job queue. The "producer" half is
 * {@code FanOutService.enqueue(StreamEvent)}.
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
     * Returns immediately if no PENDING jobs exist, or if another worker
     * won the claim race for the returned job.
     */
    private Mono<Void> claimAndProcess() {
        return jobRepository.pollPending(CLAIM_LIMIT)
                .next()  // take first (and only, since CLAIM_LIMIT=1)
                .flatMap(job -> claimJob(job)
                        .flatMap(updated -> updated != null && updated > 0
                                ? loadAndDispatch(job)
                                : Mono.empty()));
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
                                // A chunk may have marked the job DEAD (exceeded
                                // maxRetries). Preserve that terminal DLQ state —
                                // do not overwrite it with COMPLETED.
                                if (FanOutJob.STATE_DEAD.equals(job.getState())) {
                                    log.info("Fan-out job marked DEAD during chunk "
                                            + "processing — preserving state: "
                                            + "jobId={} processed={}/{}",
                                            job.getId(), job.getProcessedSubscribers(),
                                            job.getTotalSubscribers());
                                    return Mono.empty();
                                }
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
     * <p>After {@code maxRetries} failures: mark the job DEAD (DLQ).
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
                .onErrorResume(err -> handleChunkFailure(job, chunk, err, now));
    }

    /**
     * Handle a failed chunk dispatch — mirrors {@code OutboxPoller.handleFailure()}.
     *
     * <p>Increments the retry count and marks the job FAILED (NACK) so it is
     * picked up again on the next poll cycle. Once {@code nextRetry} exceeds
     * {@code maxRetries}, the job is marked DEAD (DLQ) and processing stops.
     *
     * <p>The returned error is propagated (after the FAILED state is persisted)
     * so the remaining chunks of this cycle are not processed — the job will
     * re-process all subscribers on retry.
     */
    private Mono<Void> handleChunkFailure(FanOutJob job, List<Subscription> chunk,
                                          Throwable err, OffsetDateTime now) {
        log.warn("Fan-out chunk failed: jobId={} chunkSize={} error={}",
                job.getId(), chunk.size(), err.getMessage());

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
                    err.getMessage(), now);
        }

        // NACK — set FAILED, retry on next poll cycle
        job.recordFailure(err.getMessage(), now);
        return jobRepository.updateState(
                job.getId(), FanOutJob.STATE_FAILED,
                nextRetry,
                job.getProcessedSubscribers(),
                job.getTotalSubscribers(),
                job.getLastError(), now)
                .then(Mono.error(err));  // propagate to stop chunk processing
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Build a minimal StreamEvent for NotificationService.createForFollower().
     * Only the fields that createForFollower reads need to be populated.
     *
     * <p>Note: the fan-out job does not retain the original stream ID, so
     * {@code targetId} (the broadcaster subject) is used for the streamId
     * slot — it is only string-interpolated into the notification metadata.
     */
    private static StreamEvent buildStreamEvent(FanOutJob job) {
        return new StreamEvent(
                job.getJobType(),                    // eventType
                job.getTargetId(),                   // streamId (targetId = broadcaster subject)
                job.getEventId(),                    // eventId
                OffsetDateTime.now(ZoneOffset.UTC),  // timestamp
                job.getBroadcasterSubject(),         // broadcasterSubject
                null,                                // broadcasterUsername — createForFollower falls back
                null,                                // autoArchiveChat
                null);                               // chatArchiveDelayMinutes
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
}
