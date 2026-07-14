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
 * R2DBC entity for {@code notification.notification}.
 *
 * <p>Each row represents a single notification event scoped to a recipient.
 * Notifications are immutable once created — the only mutation is marking
 * as read via {@link #markRead()}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notification")
public class Notification implements Persistable<UUID> {

    @Id
    private UUID id;

    /** R2DBC INSERT/UPDATE discriminator — set to {@code true} for new entities. */
    @Transient
    private boolean isNew;

    /** The JWT {@code sub} of the user who should receive this notification. */
    @Column("recipient_subject")
    private String recipientSubject;

    /** High-level category for routing and display grouping. */
    private NotificationCategory category;

    /**
     * Stable machine-readable action key (e.g. {@code "stream.started"},
     * {@code "stream.ended"}). Used by clients to decide rendering and
     * deep-link targets.
     */
    private String action;

    /** Short headline shown in the notification card. */
    private String title;

    /** Body text — the main content of the notification. */
    private String body;

    /**
     * Arbitrary JSON payload for client-specific data (e.g. stream ID for
     * deep-linking, actor username for display). Stored as a plain string
     * to avoid JSONB converter complexity at the entity layer.
     */
    private String metadata;

    @Column("is_read")
    private boolean isRead;

    @Column("created_at")
    private OffsetDateTime createdAt;

    // -- factory -----------------------------------------------------------

    /**
     * Create a new notification.
     *
     * @param recipientSubject the JWT {@code sub} of the target user
     * @param category         high-level notification category
     * @param action           machine-readable action key
     * @param title            short headline
     * @param body             body text
     * @param now              creation timestamp
     */
    public static Notification create(
            String recipientSubject,
            NotificationCategory category,
            String action,
            String title,
            String body,
            OffsetDateTime now) {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setNew(true);
        n.setRecipientSubject(recipientSubject);
        n.setCategory(category);
        n.setAction(action);
        n.setTitle(title);
        n.setBody(body);
        n.setRead(false);
        n.setCreatedAt(now);
        return n;
    }

    /**
     * Create a notification with attached metadata JSON.
     *
     * @see #create(String, NotificationCategory, String, String, String, OffsetDateTime)
     */
    public static Notification create(
            String recipientSubject,
            NotificationCategory category,
            String action,
            String title,
            String body,
            String metadata,
            OffsetDateTime now) {
        Notification n = create(recipientSubject, category, action, title, body, now);
        n.setMetadata(metadata);
        return n;
    }

    // -- domain ------------------------------------------------------------

    /** Mark this notification as read. Idempotent — calling on an already-read notification is a no-op. */
    public void markRead() {
        this.isRead = true;
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
