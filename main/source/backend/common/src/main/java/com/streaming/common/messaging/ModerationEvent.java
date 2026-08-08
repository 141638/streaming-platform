package com.streaming.common.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Canonical event envelope published to Kafka on moderation actions.
 *
 * <p>Emitted by chat-service when a moderator bans, unbans, or changes the
 * duration of a ban. Consumed by notification-service to push real-time
 * notifications to the banned user and the room owner.
 *
 * <p>Partition key is {@link #subject()} (the banned user's JWT sub) so a
 * single user's events stay ordered within a partition.
 *
 * <p><b>Delivery:</b> at-least-once, fire-and-forget from the producer side.
 * The {@code chat_ban} row in PostgreSQL is the source of truth; event loss
 * degrades gracefully to the Wave-1 reactive enforcement floor (403 on send).
 * Consumer dedup is via Redis {@code SETNX} on {@code eventId}.
 */
public record ModerationEvent(
        String eventType,
        String roomKey,
        String subject,
        String bannedUsername,
        String bannedBy,
        String bannedByUsername,
        String broadcasterSubject,
        String broadcasterUsername,
        String reason,
        String expiresAt,
        String eventId,
        String occurredAt
) {

    // -- factories -----------------------------------------------------------

    /**
     * A user was banned from a room.
     *
     * @param roomKey              the room's external key (stream session key)
     * @param subject              the banned user's JWT sub (partition key)
     * @param bannedUsername       the banned user's display name (nullable)
     * @param bannedBy             the moderator's JWT sub
     * @param bannedByUsername     the moderator's display name
     * @param broadcasterSubject   the room owner's JWT sub
     * @param broadcasterUsername  the room owner's display name (nullable)
     * @param reason               ban reason (nullable)
     * @param expiresAt            ISO-8601 expiry or null for permanent
     */
    public static ModerationEvent banned(
            String roomKey,
            String subject,
            String bannedUsername,
            String bannedBy,
            String bannedByUsername,
            String broadcasterSubject,
            String broadcasterUsername,
            String reason,
            String expiresAt) {
        return new ModerationEvent(
                "BANNED", roomKey, subject, bannedUsername, bannedBy, bannedByUsername,
                broadcasterSubject, broadcasterUsername, reason, expiresAt,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    /**
     * A user was manually unbanned from a room (before natural expiry).
     *
     * @param bannedUsername the banned user's display name (nullable — may not
     *                       be available if the ban row was already deleted)
     */
    public static ModerationEvent unbanned(
            String roomKey,
            String subject,
            String bannedUsername,
            String bannedBy,
            String bannedByUsername,
            String broadcasterSubject,
            String broadcasterUsername) {
        return new ModerationEvent(
                "UNBANNED", roomKey, subject, bannedUsername, bannedBy, bannedByUsername,
                broadcasterSubject, broadcasterUsername, null, null,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    /**
     * An existing ban's duration was re-based in place (e.g. 1h → 24h).
     * The event type is still {@code BANNED} with the new {@code expiresAt}
     * so the client can refresh its countdown. Consumer-side coalescing
     * (latest-wins by room+subject) prevents duplicate notifications.
     */
    public static ModerationEvent durationChanged(
            String roomKey,
            String subject,
            String bannedUsername,
            String bannedBy,
            String bannedByUsername,
            String broadcasterSubject,
            String broadcasterUsername,
            String reason,
            String expiresAt) {
        return new ModerationEvent(
                "BANNED", roomKey, subject, bannedUsername, bannedBy, bannedByUsername,
                broadcasterSubject, broadcasterUsername, reason, expiresAt,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneOffset.UTC).toString());
    }
}
