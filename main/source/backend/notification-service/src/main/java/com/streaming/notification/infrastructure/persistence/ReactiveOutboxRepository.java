package com.streaming.notification.infrastructure.persistence;

import com.streaming.notification.domain.OutboxEntry;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link OutboxEntry} entities.
 *
 * <p>Mirrors {@code stream-service:OutboxEventRepository} — same
 * {@code FOR UPDATE SKIP LOCKED} polling strategy for safe concurrent
 * access across multiple poller instances.
 */
public interface ReactiveOutboxRepository
        extends ReactiveCrudRepository<OutboxEntry, UUID> {

    /**
     * Claim the oldest PENDING entries with a row-level lock.
     * Multiple poller instances can safely coexist — each gets
     * a disjoint set of rows.
     *
     * @param limit maximum number of rows to claim
     * @return claimed PENDING entries ordered by creation time
     */
    @Query("""
            SELECT * FROM notification.notification_outbox
            WHERE state = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    Flux<OutboxEntry> pollPending(int limit);

    /**
     * Update the state and retry metadata for a processed entry.
     *
     * @param id            the entry ID
     * @param state         new state ({@code "SENT"}, {@code "FAILED"}, {@code "DEAD"})
     * @param retryCount    updated retry count
     * @param lastAttemptAt when the attempt was made
     */
    @Modifying
    @Query("""
            UPDATE notification.notification_outbox
            SET state = :state, retry_count = :retryCount, last_attempt_at = :lastAttemptAt
            WHERE id = :id
            """)
    Mono<Void> updateState(UUID id, String state, int retryCount, OffsetDateTime lastAttemptAt);
}
