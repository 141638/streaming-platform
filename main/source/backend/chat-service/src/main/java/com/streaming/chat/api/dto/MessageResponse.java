package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatMessage;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a chat message returned to API clients.
 */
public record MessageResponse(
        UUID id,
        String roomKey,
        String authorSubject,
        String body,
        OffsetDateTime createdAt
) {
    /**
     * Build a response from a persisted message and the room's external key.
     */
    public static MessageResponse from(ChatMessage msg, String roomExternalKey) {
        return new MessageResponse(
                msg.getId(),
                roomExternalKey,
                msg.getAuthorSubject(),
                msg.getBody(),
                msg.getCreatedAt()
        );
    }
}
