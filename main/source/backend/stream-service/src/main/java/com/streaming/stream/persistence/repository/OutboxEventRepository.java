package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.OutboxEvent;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive repository for the outbox table.
 *
 * <p>{@link #findUnpublished} uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * poller instances can safely coexist without double-publishing the same row.
 */
public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {

    /**
     * Fetch the next batch of unpublished events, locking the selected rows
     * against concurrent pollers.
     *
     * @param limit maximum number of rows to fetch
     * @return unpublished events ordered by creation time
     */
    @Query("""
            SELECT * FROM stream.outbox
            WHERE published = false
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """)
    Flux<OutboxEvent> findUnpublished(int limit);
}
