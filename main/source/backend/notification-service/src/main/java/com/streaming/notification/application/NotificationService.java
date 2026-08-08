package com.streaming.notification.application;

import com.streaming.common.messaging.ModerationEvent;
import com.streaming.common.messaging.StreamEvent;
import com.streaming.notification.domain.Notification;
import com.streaming.notification.domain.NotificationCategory;
import com.streaming.notification.infrastructure.persistence.ReactiveNotificationRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service for notification operations.
 *
 * <p>Owns the notification lifecycle: creation from Kafka events, retrieval
 * for the bell list, and the mark-as-read mutation. All queries are scoped
 * to the calling user via {@code recipientSubject}.
 *
 * <p>This is the unification point where inbound events from different Kafka
 * topics are mapped into a common {@link Notification} entity. Currently
 * only stream lifecycle events are handled; chat moderation and mentions
 * will be added in Wave 2.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int DEFAULT_LIMIT = 20;

    private final ReactiveNotificationRepository notificationRepository;
    private final NotificationDispatcher dispatcher;

    // ── Create ──────────────────────────────────────────────────────────

    /**
     * Create a notification from a stream lifecycle event.
     *
     * <p>Currently only {@code STREAM_STARTED} and {@code STREAM_ENDED} produce
     * user-facing notifications. The other event types are informational and
     * are logged but not persisted to the notification table.
     *
     * @param event the deserialized stream event from Kafka
     * @return empty Mono — this is a side effect that completes when the notification is persisted
     */
    public Mono<Void> createFromStreamEvent(StreamEvent event) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return switch (event.eventType()) {
            case "STREAM_STARTED" -> {
                Notification n = Notification.create(
                        event.broadcasterSubject(),
                        NotificationCategory.STREAM_LIVE,
                        "stream.started",
                        "Your stream is now live",
                        "Stream " + event.streamId() + " is now broadcasting.",
                        "{\"streamId\":\"" + event.streamId() + "\"}",
                        now);
                yield dispatcher.deliver(n);
            }
            case "STREAM_ENDED" -> {
                Notification n = Notification.create(
                        event.broadcasterSubject(),
                        NotificationCategory.STREAM_ENDED,
                        "stream.ended",
                        "Your stream has ended",
                        "Stream " + event.streamId() + " has finished broadcasting.",
                        "{\"streamId\":\"" + event.streamId() + "\"}",
                        now);
                yield dispatcher.deliver(n);
            }
            case "STREAM_CREATED" -> {
                log.debug("STREAM_CREATED — no user-facing notification: streamId={}",
                        event.streamId());
                yield Mono.empty();
            }
            case "STREAM_SCHEDULED" -> {
                log.debug("STREAM_SCHEDULED — no user-facing notification: streamId={}",
                        event.streamId());
                yield Mono.empty();
            }
            case "STREAM_CANCELLED" -> {
                log.debug("STREAM_CANCELLED — no user-facing notification: streamId={}",
                        event.streamId());
                yield Mono.empty();
            }
            default -> {
                log.warn("Unknown event type — no notification: type={} eventId={}",
                        event.eventType(), event.eventId());
                yield Mono.empty();
            }
        };
    }

    /**
     * Create a follower notification for fan-out. Called when a followed
     * broadcaster starts a stream.
     *
     * @param event             the stream event carrying broadcaster info
     * @param followerSubject   the subscriber who follows the broadcaster
     * @return a persisted-and-delivered notification
     */
    public Mono<Notification> createForFollower(StreamEvent event, String followerSubject) {
        String username = event.broadcasterUsername() != null
                ? event.broadcasterUsername()
                : "A streamer you follow";
        Notification n = Notification.create(
                followerSubject,
                NotificationCategory.STREAM_LIVE,
                "stream.started",
                username + " is now live",
                "Stream started broadcasting.",
                "{\"streamId\":\"" + event.streamId() + "\"}",
                OffsetDateTime.now(ZoneOffset.UTC));
        return Mono.just(n);
    }

    /**
     * Create a notification for the banned user from a moderation event.
     *
     * <p>Recipient is {@code event.subject()} (the banned user). The action
     * key is {@code chat.banned} or {@code chat.unbanned} depending on the
     * event type, so the frontend can decide rendering and deep-link targets.
     *
     * @param event the moderation event from Kafka
     * @return empty Mono that completes when the notification is persisted and delivered
     */
    public Mono<Void> createModerationNotification(ModerationEvent event) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String action = "UNBANNED".equals(event.eventType()) ? "chat.unbanned" : "chat.banned";

        String title;
        String body;
        if ("UNBANNED".equals(event.eventType())) {
            title = "Ban lifted";
            body = "Your ban in room " + event.roomKey() + " has been lifted.";
        } else if (event.expiresAt() != null) {
            title = "You have been banned";
            body = "You have been temporarily banned from room " + event.roomKey()
                    + (event.reason() != null ? " for: " + event.reason() : "") + ".";
        } else {
            title = "You have been permanently banned";
            body = "You have been permanently banned from room " + event.roomKey()
                    + (event.reason() != null ? " for: " + event.reason() : "") + ".";
        }

        String metadata = moderationMetadata(event);

        Notification n = Notification.create(
                event.subject(),
                NotificationCategory.CHAT_MODERATION,
                action,
                title,
                body,
                metadata,
                now);
        return dispatcher.deliver(n);
    }

    /**
     * Create a moderator alert for the room owner (broadcaster).
     *
     * <p>Recipient is {@code event.broadcasterSubject()}. The action key is
     * {@code chat.moderator_alert} so the frontend can navigate to the room
     * when the card is clicked.
     *
     * @param event the moderation event from Kafka
     * @return empty Mono that completes when the alert is persisted and delivered
     */
    public Mono<Void> createModeratorAlert(ModerationEvent event) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String bannedName = event.bannedUsername() != null
                ? event.bannedUsername()
                : event.subject();
        String moderatorName = event.bannedByUsername() != null
                ? event.bannedByUsername()
                : event.bannedBy();

        String title;
        String body;
        if ("UNBANNED".equals(event.eventType())) {
            title = moderatorName + " unbanned " + bannedName;
            body = bannedName + " has been unbanned from your room.";
        } else if (event.expiresAt() != null) {
            title = moderatorName + " banned " + bannedName;
            body = "Temporary ban"
                    + (event.reason() != null ? " for: " + event.reason() : "") + ".";
        } else {
            title = moderatorName + " permanently banned " + bannedName;
            body = "Permanent ban"
                    + (event.reason() != null ? " for: " + event.reason() : "") + ".";
        }

        String metadata = moderationMetadata(event);

        Notification n = Notification.create(
                event.broadcasterSubject(),
                NotificationCategory.CHAT_MODERATION,
                "chat.moderator_alert",
                title,
                body,
                metadata,
                now);
        return dispatcher.deliver(n);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Build the metadata JSON payload shared by both the banned-user
     * notification and the moderator alert.
     */
    private static String moderationMetadata(ModerationEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"roomKey\":\"").append(escapeJson(event.roomKey())).append('"');
        if (event.reason() != null) {
            json.append(",\"reason\":\"").append(escapeJson(event.reason())).append('"');
        }
        if (event.expiresAt() != null) {
            json.append(",\"expiresAt\":\"").append(escapeJson(event.expiresAt())).append('"');
        }
        json.append(",\"bannedByUsername\":\"")
                .append(escapeJson(event.bannedByUsername() != null ? event.bannedByUsername() : ""))
                .append('"');
        json.append(",\"bannedBySubject\":\"")
                .append(escapeJson(event.bannedBy() != null ? event.bannedBy() : ""))
                .append('"');
        if (event.bannedUsername() != null) {
            json.append(",\"bannedUsername\":\"").append(escapeJson(event.bannedUsername())).append('"');
        }
        json.append(",\"bannedSubject\":\"").append(escapeJson(event.subject())).append('"');
        json.append('}');
        return json.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Read ────────────────────────────────────────────────────────────

    /**
     * Get notifications for a recipient, newest first.
     *
     * <p>When {@code cursor} is non-null, returns notifications older than
     * the cursor timestamp. The cursor is an ISO-8601 instant string from
     * the {@code createdAt} field of the last-seen notification.
     *
     * @param recipientSubject the JWT {@code sub} of the calling user
     * @param cursor           ISO-8601 timestamp for cursor pagination, or null for first page
     * @param limit            max notifications to return (default 20)
     * @return the notification list, newest first (empty if none)
     */
    public Mono<List<Notification>> getNotifications(
            String recipientSubject, String cursor, int limit) {
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        if (cursor != null && !cursor.isBlank()) {
            OffsetDateTime before = parseCursor(cursor);
            return notificationRepository
                    .findByRecipientSubjectAndCreatedAtBeforeOrderByCreatedAtDesc(
                            recipientSubject, before)
                    .take(effectiveLimit)
                    .collectList();
        }
        return notificationRepository
                .findByRecipientSubjectOrderByCreatedAtDesc(recipientSubject)
                .take(effectiveLimit)
                .collectList();
    }

    /**
     * Count unread notifications for the bell badge.
     *
     * @param recipientSubject the JWT {@code sub} of the calling user
     * @return the count (0 if none)
     */
    public Mono<Long> getUnreadCount(String recipientSubject) {
        return notificationRepository.countByRecipientSubjectAndIsReadFalse(recipientSubject)
                .defaultIfEmpty(0L);
    }

    // ── Mutate ──────────────────────────────────────────────────────────

    /**
     * Mark a notification as read. Ownership is enforced at the query level —
     * the notification must belong to the calling user.
     *
     * @param id               the notification ID
     * @param recipientSubject the JWT {@code sub} of the calling user
     * @return the updated notification, or error if not found
     */
    public Mono<Notification> markAsRead(UUID id, String recipientSubject) {
        return notificationRepository.findByIdAndRecipientSubject(id, recipientSubject)
                .switchIfEmpty(Mono.error(new NotificationNotFoundException(id, recipientSubject)))
                .flatMap(notification -> {
                    if (notification.isRead()) {
                        log.debug("Notification already read: id={}", id);
                        return Mono.just(notification);
                    }
                    notification.markRead();
                    return notificationRepository.save(notification)
                            .doOnSuccess(saved -> log.debug(
                                    "Notification marked as read: id={} recipient={}",
                                    saved.getId(), saved.getRecipientSubject()));
                });
    }

    /**
     * Mark all of a user's unread notifications as read in a single bulk
     * UPDATE. More efficient than fetching and saving each row individually.
     *
     * @param recipientSubject the JWT {@code sub} of the calling user
     * @return the number of notifications that were marked as read (0 if none)
     */
    public Mono<Long> markAllAsRead(String recipientSubject) {
        return notificationRepository.markAllAsReadByRecipientSubject(recipientSubject)
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.debug("Marked {} notifications as read: recipient={}",
                                count, recipientSubject);
                    }
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static OffsetDateTime parseCursor(String cursor) {
        try {
            return Instant.parse(cursor).atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Invalid cursor, falling back to now: {}", cursor);
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    // ── exceptions ──────────────────────────────────────────────────────

    public static class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(UUID id, String recipientSubject) {
            super("Notification not found: id=" + id + " recipient=" + recipientSubject);
        }
    }
}
