package com.streaming.stream.persistence.entity;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row per stream per minute, recording the peak concurrent viewer count
 * observed during that minute bucket.
 *
 * <p>Harvested every 30s from Redis presence keys by
 * {@code HeartbeatHarvestService}. UPSERT with {@code GREATEST()} ensures
 * the peak within each minute is captured.
 */
@Table("stream_viewer_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StreamViewerSnapshotEntity {

    @Id
    private Long id;

    @Column("stream_id")
    private UUID streamId;

    @Column("minute_bucket")
    private OffsetDateTime minuteBucket;

    @Column("viewer_count")
    private long viewerCount;

    @Column("harvested_at")
    private OffsetDateTime harvestedAt;
}
