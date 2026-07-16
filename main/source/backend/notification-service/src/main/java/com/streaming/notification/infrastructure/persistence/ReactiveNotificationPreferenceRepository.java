package com.streaming.notification.infrastructure.persistence;

import com.streaming.notification.domain.NotificationPreference;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for {@link NotificationPreference} entities.
 *
 * <p>All queries are scoped by {@code subscriberSubject} — a user can only
 * manage their own preferences.
 */
public interface ReactiveNotificationPreferenceRepository
        extends ReactiveCrudRepository<NotificationPreference, UUID> {

    /** All preferences for a user (active and inactive). */
    Flux<NotificationPreference> findBySubscriberSubject(String subscriberSubject);

    /** Only active preferences for a user. */
    Flux<NotificationPreference> findBySubscriberSubjectAndActiveTrue(String subscriberSubject);

    /**
     * Specific (user, channel) pair — used for upsert. One row per channel
     * per user per the unique constraint {@code uq_notification_preference}.
     */
    Mono<NotificationPreference> findBySubscriberSubjectAndChannel(
            String subscriberSubject, String channel);
}
