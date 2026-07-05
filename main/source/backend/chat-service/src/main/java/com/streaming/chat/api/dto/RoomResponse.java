package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatRoom;
import java.time.OffsetDateTime;

/**
 * Public-facing room metadata returned to clients.
 * The {@code status} field is the wire value ({@code ACTIVE} / {@code ARCHIVED}).
 */
public record RoomResponse(
        String externalKey,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime archivedAt
) {
    public static RoomResponse from(ChatRoom room) {
        return new RoomResponse(
                room.getExternalKey(),
                room.getStatus().wireValue(),
                room.getCreatedAt(),
                room.getArchivedAt()
        );
    }
}
