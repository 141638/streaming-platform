package com.streaming.common.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Canonical event envelope published to Kafka on stream lifecycle transitions.
 *
 * <p>Factory methods enforce correct event type strings and generate a unique
 * {@code eventId} per publication for consumer deduplication.
 */
public record StreamEvent(
        String eventType,
        String streamId,
        String eventId,
        OffsetDateTime timestamp,
        String broadcasterSubject,
        String broadcasterUsername,
        Boolean autoArchiveChat,
        Integer chatArchiveDelayMinutes
) {
    public static StreamEvent created(UUID streamId, String broadcasterSubject,
                                       String broadcasterUsername) {
        return new StreamEvent("STREAM_CREATED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername, null, null);
    }

    public static StreamEvent scheduled(UUID streamId, String broadcasterSubject,
                                         String broadcasterUsername) {
        return new StreamEvent("STREAM_SCHEDULED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername, null, null);
    }

    public static StreamEvent started(UUID streamId, String broadcasterSubject,
                                       String broadcasterUsername) {
        return new StreamEvent("STREAM_STARTED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername, null, null);
    }

    public static StreamEvent ended(UUID streamId, String broadcasterSubject,
                                     String broadcasterUsername,
                                     boolean autoArchiveChat, int chatArchiveDelayMinutes) {
        return new StreamEvent("STREAM_ENDED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername,
                autoArchiveChat, chatArchiveDelayMinutes);
    }

    public static StreamEvent cancelled(UUID streamId, String broadcasterSubject,
                                         String broadcasterUsername) {
        return new StreamEvent("STREAM_CANCELLED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername, null, null);
    }

    /**
     * Published by ChatArchiveScheduler when the auto-archive delay has elapsed
     * for an ended stream. Chat-service should archive the room on receipt.
     */
    public static StreamEvent chatArchiveTriggered(UUID streamId, String broadcasterSubject,
                                                    String broadcasterUsername) {
        return new StreamEvent("CHAT_ARCHIVE_TRIGGERED", streamId.toString(),
                UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                broadcasterSubject, broadcasterUsername, null, null);
    }
}
