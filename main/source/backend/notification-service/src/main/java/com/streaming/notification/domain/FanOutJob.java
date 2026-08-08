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
