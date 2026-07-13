package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.MessageType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Public representation of a chat message returned to API clients.
 */
public record MessageResponse(
        UUID id,
        String roomKey,
        String authorSubject,
        String authorUsername,
        String authorAvatarUrl,
        String body,
        String messageType,
        BigDecimal giftAmount,
        String giftCurrency,
        OffsetDateTime createdAt,
        List<String> mentions
) {
    /**
     * Build a response from a persisted message and the room's external key.
     */
    public static MessageResponse from(ChatMessage msg, String roomExternalKey) {
        return new MessageResponse(
                msg.getId(),
                roomExternalKey,
                msg.getAuthorSubject(),
                msg.getAuthorUsername(),
                msg.getAuthorAvatarUrl(),
                msg.getBody(),
                msg.getMessageType() != null ? msg.getMessageType().wireValue() : MessageType.NORMAL.wireValue(),
                msg.getGiftAmount(),
                msg.getGiftCurrency(),
                msg.getCreatedAt(),
                msg.getMentions() != null ? Arrays.asList(msg.getMentions()) : Collections.emptyList()
        );
    }
}
