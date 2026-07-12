package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatBan;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public representation of a room ban returned to moderation API clients.
 */
public record BanResponse(
        UUID id,
        UUID roomId,
        String bannedSubject,
        String bannedUsername,
        String bannedBySubject,
        String bannedByUsername,
        String reason,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
    public static BanResponse from(ChatBan ban) {
        return new BanResponse(
                ban.getId(),
                ban.getRoomId(),
                ban.getBannedSubject(),
                ban.getBannedUsername(),
                ban.getBannedBySubject(),
                ban.getBannedByUsername(),
                ban.getReason(),
                ban.getCreatedAt(),
                ban.getExpiresAt()
        );
    }
}
