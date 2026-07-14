package com.streaming.notification.infrastructure.persistence;

import com.streaming.notification.domain.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link Notification} entities.
 *
 * <p>All queries are scoped by {@code recipientSubject} — a user can only
 * see their own notifications. Ownership enforcement is at the query level.
 */
public interface ReactiveNotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    /**
     * Find the most recent notifications for a recipient, newest first.
     * Used for the initial bell-list load.
     */
    Flux<Notification> findByRecipientSubjectOrderByCreatedAtDesc(String recipientSubject);

    /**
     * Cursor-based pagination — notifications older than the given cursor.
     * Returns up to {@code limit} rows (controlled by the caller via {@code .take()}).
     */
    Flux<Notification> findByRecipientSubjectAndCreatedAtBeforeOrderByCreatedAtDesc(
            String recipientSubject, OffsetDateTime before);

    /**
     * Count of unread notifications for the bell badge.
     * Backed by the partial index {@code ix_notification_recipient_unread}.
     */
    Mono<Long> countByRecipientSubjectAndIsReadFalse(String recipientSubject);

    /**
     * Ownership-scoped lookup — mark-as-read must verify the notification
     * belongs to the calling user.
     */
    Mono<Notification> findByIdAndRecipientSubject(UUID id, String recipientSubject);
}
