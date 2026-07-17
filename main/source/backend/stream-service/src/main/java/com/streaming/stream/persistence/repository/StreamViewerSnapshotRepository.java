package com.streaming.stream.persistence.repository;

import com.streaming.stream.persistence.entity.StreamViewerSnapshotEntity;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link StreamViewerSnapshotEntity}.
 *
 * <p>The UPSERT query uses {@code GREATEST()} to capture the peak viewer count
 * within each minute bucket — if two harvest cycles fall in the same minute,
 * the higher count wins.
 */
@Repository
public interface StreamViewerSnapshotRepository
        extends ReactiveCrudRepository<StreamViewerSnapshotEntity, Long> {

    /**
     * Upsert a viewer count snapshot for a stream at the current minute bucket.
     *
     * @param streamId     the stream being harvested
     * @param minuteBucket the minute boundary (truncated to minute)
     * @param count        the concurrent viewer count at harvest time
     * @return empty Mono on completion
     */
    @Query("""
            INSERT INTO stream.stream_viewer_snapshot
                (stream_id, minute_bucket, viewer_count)
            VALUES (:streamId, :minuteBucket, :count)
            ON CONFLICT (stream_id, minute_bucket)
            DO UPDATE SET viewer_count = GREATEST(
                stream.stream_viewer_snapshot.viewer_count,
                EXCLUDED.viewer_count
            )
            """)
    Mono<Void> upsert(UUID streamId, java.time.OffsetDateTime minuteBucket, long count);
}
