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
 * R2DBC entity for {@code notification.notification_outbox}.
 *
 * <p>Each row represents a notification that needs reliable delivery to a
 * downstream channel (email, push). Written by {@code OutboxService.enqueue()}
 * and picked up by {@code OutboxPoller} on a scheduled interval.
 *
 * <p>Follows the same pattern as {@code stream-service:OutboxEvent} —
 * {@code FOR UPDATE SKIP LOCKED} polling, retry with max attempts, and
 * dead-letter marking for unprocessable entries.
 *
 * <p>The {@code payload} column stores the serialized notification JSON
 * as plain TEXT (migrated from JSONB in V5 to avoid R2DBC wire-type issues).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notification_outbox")
public class OutboxEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    /** The type of aggregate that produced this event ({@code "notification"}). */
    @Column("aggregate_type")
    private String aggregateType;

    /** The UUID of the aggregate (the notification ID). */
    @Column("aggregate_id")
    private String aggregateId;

    /** Serialized notification JSON, stored as plain TEXT (migrated from JSONB in V5). */
    private String payload;

    /**
     * Processing state: {@code "PENDING"}, {@code "SENT"}, {@code "FAILED"},
     * or {@code "DEAD"} (exceeded max retries).
     */
    private String state;

    /** Number of delivery attempts. Incremented on each failure. */
    @Column("retry_count")
    private int retryCount;

    /** When the last delivery attempt was made, or {@code null} if never attempted. */
    @Column("last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    // -- factory -----------------------------------------------------------

    /**
     * Create a new outbox entry in PENDING state.
     *
     * @param aggregateType the aggregate type ({@code "notification"})
     * @param aggregateId   the aggregate UUID string
     * @param payload       the serialized notification JSON
     * @param now           creation timestamp
     */
    public static OutboxEntry create(
            String aggregateType,
            String aggregateId,
            String payload,
            OffsetDateTime now) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(UUID.randomUUID());
        entry.setNew(true);
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setPayload(payload);
        entry.setState("PENDING");
        entry.setRetryCount(0);
        entry.setCreatedAt(now);
        return entry;
    }

    // -- domain ------------------------------------------------------------

    /** Mark this entry as successfully delivered. */
    public void markSent(OffsetDateTime now) {
        this.state = "SENT";
        this.lastAttemptAt = now;
    }

    /** Record a failed delivery attempt. */
    public void recordFailure(OffsetDateTime now) {
        this.retryCount++;
        this.lastAttemptAt = now;
        this.state = "FAILED";
    }

    /** Mark as dead after exceeding max retries. */
    public void markDead(OffsetDateTime now) {
        this.state = "DEAD";
        this.lastAttemptAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
