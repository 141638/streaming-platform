package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatRoom;
import java.time.OffsetDateTime;

/**
 * Public-facing room metadata returned to clients.
 * The {@code status} field is the wire value ({@code ACTIVE} / {@code ARCHIVED}).
 *
 * <p>{@code viewerCanModerate} is a per-caller capability signal: {@code true}
 * when the requesting JWT holds the {@code chat:moderation moderate} authority
 * for this room's owner. It is computed from the {@code ent} claim
 * <b>independently of the {@code chat.pbac.enabled} enforcement flag</b>, so the
 * client can render moderator affordances even while PBAC ships dark.
 */
public record RoomResponse(
        String externalKey,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime archivedAt,
        boolean viewerCanModerate
) {
    public static RoomResponse from(ChatRoom room, boolean viewerCanModerate) {
        return new RoomResponse(
                room.getExternalKey(),
                room.getStatus().wireValue(),
                room.getCreatedAt(),
                room.getArchivedAt(),
                viewerCanModerate
        );
    }
}
