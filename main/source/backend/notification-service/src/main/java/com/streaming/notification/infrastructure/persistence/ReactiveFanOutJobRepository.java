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
