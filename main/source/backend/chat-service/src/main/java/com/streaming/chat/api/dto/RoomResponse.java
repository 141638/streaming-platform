package com.streaming.chat.api.dto;

import com.streaming.chat.domain.ChatRoom;
import java.time.OffsetDateTime;

/**
 * Public-facing room metadata returned to clients.
 * The {@code status} field is the wire value ({@code ACTIVE} / {@code ARCHIVED}).
 *
 * <p>{@code broadcasterSubject} identifies the streamer who owns this room so
 * the client can highlight their messages distinct from regular viewers.
 *
 * <p>{@code viewerCanModerate} is a per-caller capability signal: {@code true}
 * when the requesting JWT holds the {@code chat:moderation moderate} authority
 * for this room's owner. It is computed from the {@code ent} claim
 * <b>independently of the {@code chat.pbac.enabled} enforcement flag</b>, so the
 * client can render moderator affordances even while PBAC ships dark.
 *
 * <p>{@code viewerBanned} is a per-caller resource-state signal: {@code true}
 * when the requesting user has an <em>active</em> ban in this room. It lets the
 * client disable the composer on room load — the enforcement floor without a
 * failed send — independently of the (future) push pipeline. An unauthenticated
 * caller ({@code jwt == null}) yields {@code false}.
 */
public record RoomResponse(
        String externalKey,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime archivedAt,
        String broadcasterSubject,
        boolean viewerCanModerate,
        boolean viewerBanned
) {
    public static RoomResponse from(ChatRoom room, boolean viewerCanModerate, boolean viewerBanned) {
        return new RoomResponse(
                room.getExternalKey(),
                room.getStatus().wireValue(),
                room.getCreatedAt(),
                room.getArchivedAt(),
                room.getBroadcasterSubject(),
                viewerCanModerate,
                viewerBanned
        );
    }
}
