package com.streaming.stream.persistence.entity;

import io.r2dbc.postgresql.codec.Json;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row in the outbox table — one event to be delivered to Kafka.
 *
 * <p>Written in the same database transaction as the triggering entity change
 * (e.g. stream status transition). Picked up and published by
 * {@code OutboxPoller} on a fixed-delay schedule.
 *
 * <p>The {@code payload} column stores the serialized {@code StreamEvent}
 * JSON. When deserialized on the consumer side, {@code eventId} is used
 * for idempotent deduplication.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "outbox")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("event_type")
    private String eventType;

    @Column("stream_id")
    private UUID streamId;

    /** Serialized {@code StreamEvent} JSON, stored natively as {@code jsonb}. */
    private Json payload;

    @Column("retry_count")
    private int retryCount;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    /** {@code true} when the event has been successfully published to Kafka. */
    private boolean published;
}
