package com.streaming.insight.domain.model;

import com.streaming.common.messaging.EngagementEvent;
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
 * R2DBC entity for {@code insight.engagement_event}.
 *
 * <p>Mirrors {@code FanOutJob} pattern — {@link Persistable} with
 * {@code @Transient isNew} for correct INSERT/UPDATE discrimination.
 * Uses a generated UUID internal PK independent of the external
 * {@code eventId} (Kafka event identifier used for dedup).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "engagement_event")
public class EngagementEventEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    @Column("event_id")
    private String eventId;

    @Column("event_type")
    private String eventType;

    @Column("stream_id")
    private UUID streamId;

    @Column("actor_subject")
    private String actorSubject;

    @Column("target_type")
    private String targetType;

    @Column("target_id")
    private String targetId;

    @Column("category")
    private String category;

    @Column("occurred_at")
    private OffsetDateTime occurredAt;

    // ── Factory ──────────────────────────────────────────────────────────

    /**
     * Create a new entity from a deserialized {@link EngagementEvent}.
     * Sets {@link #isNew} to {@code true} so R2DBC issues an INSERT.
     */
    public static EngagementEventEntity create(EngagementEvent event) {
        EngagementEventEntity entity = new EngagementEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setNew(true);
        entity.setEventId(event.eventId());
        entity.setEventType(event.eventType());
        entity.setStreamId(UUID.fromString(event.streamId()));
        entity.setActorSubject(event.actorSubject());
        entity.setTargetType(event.targetType());
        entity.setTargetId(event.targetId());
        entity.setCategory(event.category());
        entity.setOccurredAt(event.occurredAt());
        return entity;
    }

    // ── Persistable contract ─────────────────────────────────────────────

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
