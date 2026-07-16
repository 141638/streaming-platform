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
 * R2DBC entity for {@code notification.notification_preference}.
 *
 * <p>Answers "how should this user be notified?" — one row per (user, channel)
 * pair. A user with both in_app and email preferences has two rows. Changing
 * a delivery preference is a single-row mutation regardless of follow count.
 *
 * <p>The {@code topic_glob} column optionally filters by category pattern
 * (e.g. {@code "STREAM_*"} means "only stream lifecycle notifications").
 * A {@code null} value means "all categories."
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notification_preference")
public class NotificationPreference implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    /** The JWT {@code sub} of the user who owns this preference. */
    @Column("subscriber_subject")
    private String subscriberSubject;

    /** Delivery channel: {@code "in_app"}, {@code "email"}, or {@code "push"}. */
    private String channel;

    /**
     * Category filter glob (e.g. {@code "STREAM_*"}), or {@code null} for all
     * categories. Stored as a plain string — no R2DBC converter needed.
     */
    @Column("topic_glob")
    private String topicGlob;

    /** Whether this delivery channel is active. */
    private boolean active;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    // -- factory -----------------------------------------------------------

    /**
     * Create a new notification preference.
     *
     * @param subscriberSubject the JWT {@code sub} of the owning user
     * @param channel           delivery channel ({@code "in_app"}, {@code "email"}, {@code "push"})
     * @param topicGlob         category filter pattern, or {@code null} for all
     * @param now               creation timestamp
     */
    public static NotificationPreference create(
            String subscriberSubject,
            String channel,
            String topicGlob,
            OffsetDateTime now) {
        NotificationPreference p = new NotificationPreference();
        p.setId(UUID.randomUUID());
        p.setNew(true);
        p.setSubscriberSubject(subscriberSubject);
        p.setChannel(channel);
        p.setTopicGlob(topicGlob);
        p.setActive(true);
        p.setCreatedAt(now);
        return p;
    }

    // -- domain ------------------------------------------------------------

    /** Activate this delivery channel. Idempotent. */
    public void activate() {
        this.active = true;
    }

    /** Deactivate this delivery channel. Idempotent. */
    public void deactivate() {
        this.active = false;
    }

    /** Update the category filter pattern. */
    public void updateTopicGlob(String topicGlob) {
        this.topicGlob = topicGlob;
    }

    /** Record a mutation timestamp. */
    public void touch(OffsetDateTime now) {
        this.updatedAt = now;
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
