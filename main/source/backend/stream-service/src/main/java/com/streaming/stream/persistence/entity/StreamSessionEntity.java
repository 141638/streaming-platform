package com.streaming.stream.persistence.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Stream session aggregate root.
 *
 * <p>State transitions MUST go through the domain methods
 * ({@link #goLive()}, {@link #end()}, {@link #cancel()}, {@link #schedule()})
 * — never call {@link #setStatus(StreamStatus)} directly in business logic.
 * Direct {@code setStatus} is reserved for the R2DBC framework.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stream_session")
public class StreamSessionEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("stream_key_hash")
    private String streamKeyHash;

    @Column("broadcaster_subject")
    private String broadcasterSubject;

    private StreamStatus status;

    private String title;

    private String description;

    @Column("category_id")
    private UUID categoryId;

    /** Denormalized category name for backward compatibility. */
    private String category;

    /** Free-form tags stored as PostgreSQL {@code TEXT[]}. */
    private String[] tags;

    @Column("max_viewers")
    private Integer maxViewers;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("started_at")
    private OffsetDateTime startedAt;

    @Column("scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column("ended_at")
    private OffsetDateTime endedAt;

    @Version
    @Column("version")
    private Long version;

    // ── State machine ─────────────────────────────────────────────────────

    /**
     * Validate and execute a transition to the given target status.
     *
     * <p>Manages lifecycle timestamps automatically:
     * <ul>
     *   <li>{@code LIVE} → sets {@code startedAt}</li>
     *   <li>{@code ENDED} or {@code CANCELLED} → sets {@code endedAt} if not already set</li>
     * </ul>
     *
     * @param target the desired target status
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(StreamStatus target) {
        if (this.status == null) {
            throw new IllegalStateException("Cannot transition from null status");
        }
        if (this.status == target) {
            return; // no-op
        }
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid transition: " + this.status.wireValue() + " → " + target.wireValue());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        switch (target) {
            case LIVE -> this.startedAt = now;
            case ENDED, CANCELLED -> {
                if (this.endedAt == null) {
                    this.endedAt = now;
                }
            }
        }

        this.status = target;
        this.updatedAt = now;
    }

    /** DRAFT → LIVE. Sets {@code startedAt}. SCHEDULED cannot transition directly to LIVE. */
    public void goLive() {
        transitionTo(StreamStatus.LIVE);
    }

    /** LIVE → ENDED. Sets {@code endedAt}. */
    public void end() {
        transitionTo(StreamStatus.ENDED);
    }

    /** DRAFT → CANCELLED. Sets {@code endedAt}. SCHEDULED cannot be cancelled directly. */
    public void cancel() {
        transitionTo(StreamStatus.CANCELLED);
    }
}
