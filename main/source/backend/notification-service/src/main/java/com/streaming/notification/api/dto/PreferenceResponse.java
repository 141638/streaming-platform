package com.streaming.notification.api.dto;

import com.streaming.notification.domain.NotificationPreference;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a notification preference returned to API clients.
 *
 * @param id                the preference UUID
 * @param subscriberSubject the owning user's JWT {@code sub}
 * @param channel           delivery channel ({@code "in_app"}, {@code "email"}, {@code "push"})
 * @param topicGlob         category filter pattern, or {@code null} for all
 * @param active            whether this channel is active
 * @param createdAt         when the preference was created
 * @param updatedAt         when the preference was last modified
 */
public record PreferenceResponse(
        UUID id,
        String subscriberSubject,
        String channel,
        String topicGlob,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    /**
     * Build a response from a persisted preference entity.
     */
    public static PreferenceResponse from(NotificationPreference pref) {
        return new PreferenceResponse(
                pref.getId(),
                pref.getSubscriberSubject(),
                pref.getChannel(),
                pref.getTopicGlob(),
                pref.isActive(),
                pref.getCreatedAt(),
                pref.getUpdatedAt()
        );
    }
}
