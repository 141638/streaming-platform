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
 * R2DBC entity for {@code notification.subscription}.
 *
 * <p>Answers "what does this user want notifications about?" — one row per
 * (subscriber, target) pair. The target is polymorphic via
 * {@code (target_type, target_id)}:
 *
 * <ul>
 *   <li>{@code CHANNEL} — follow a streamer (target_id = broadcaster subject)</li>
 *   <li>{@code CHAT_ROOM} — notifications for a chat room</li>
 *   <li>{@code STREAM_SESSION} — notifications for a specific stream</li>
 * </ul>
 *
 * <p>The notification service does not interpret {@code target_id} — it only
 * queries it during fan-out lookups. The Follow vs Subscribe business
 * distinction is owned by stream-service (see ADR-0001 §3).
 *
 * <p>Unfollow is a soft delete — {@link #deactivate()} sets {@code active=false}
 * rather than deleting the row. This preserves the subscription history.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "subscription")
public class Subscription implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    /** The JWT {@code sub} of the subscribing user. */
    @Column("subscriber_subject")
    private String subscriberSubject;

    /**
     * The identifier of the target being followed — a broadcaster subject,
     * room key, or stream session ID.
     */
    @Column("target_id")
    private String targetId;

    /**
     * The kind of target: {@code "CHANNEL"}, {@code "CHAT_ROOM"},
     * or {@code "STREAM_SESSION"}.
     */
    @Column("target_type")
    private String targetType;

    /** Whether this subscription is active. Unfollow sets this to {@code false}. */
    private boolean active;

    @Column("created_at")
    private OffsetDateTime createdAt;

    // -- factory -----------------------------------------------------------

    /**
     * Create a new subscription (follow).
     *
     * @param subscriberSubject the JWT {@code sub} of the subscribing user
     * @param targetType        the kind of target ({@code "CHANNEL"}, etc.)
     * @param targetId          the target identifier
     * @param now               creation timestamp
     */
    public static Subscription create(
            String subscriberSubject,
            String targetType,
            String targetId,
            OffsetDateTime now) {
        Subscription s = new Subscription();
        s.setId(UUID.randomUUID());
        s.setNew(true);
        s.setSubscriberSubject(subscriberSubject);
        s.setTargetType(targetType);
        s.setTargetId(targetId);
        s.setActive(true);
        s.setCreatedAt(now);
        return s;
    }

    // -- domain ------------------------------------------------------------

    /** Soft-delete — mark this subscription as inactive (unfollow). Idempotent. */
    public void deactivate() {
        this.active = false;
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
