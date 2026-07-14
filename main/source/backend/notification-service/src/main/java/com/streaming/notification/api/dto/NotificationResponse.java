package com.streaming.notification.api.dto;

import com.streaming.notification.domain.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a notification returned to API clients.
 */
public record NotificationResponse(
        UUID id,
        String category,
        String action,
        String title,
        String body,
        String metadata,
        boolean isRead,
        OffsetDateTime createdAt
) {
    /**
     * Build a response from a persisted notification entity.
     */
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getCategory() != null ? n.getCategory().wireValue() : null,
                n.getAction(),
                n.getTitle(),
                n.getBody(),
                n.getMetadata(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
