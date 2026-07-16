package com.streaming.notification.api.dto;

import com.streaming.notification.domain.Subscription;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a subscription returned to API clients.
 *
 * @param id                the subscription UUID
 * @param subscriberSubject the JWT {@code sub} of the subscribing user
 * @param targetType        the kind of target being followed
 * @param targetId          the target identifier
 * @param active            whether the subscription is active
 * @param createdAt         when the subscription was created
 */
public record SubscriptionResponse(
        UUID id,
        String subscriberSubject,
        String targetType,
        String targetId,
        boolean active,
        OffsetDateTime createdAt
) {
    /**
     * Build a response from a persisted subscription entity.
     */
    public static SubscriptionResponse from(Subscription sub) {
        return new SubscriptionResponse(
                sub.getId(),
                sub.getSubscriberSubject(),
                sub.getTargetType(),
                sub.getTargetId(),
                sub.isActive(),
                sub.getCreatedAt()
        );
    }
}
