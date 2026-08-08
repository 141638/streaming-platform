package com.streaming.common.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Canonical event envelope for viewer engagement telemetry published to Kafka.
 *
 * <p>Mirrors {@link StreamEvent} — immutable record with static factory methods
 * that enforce correct event type strings and generate a unique {@code eventId}
 * per publication for consumer deduplication.
 *
 * <p>Unlike {@code StreamEvent} (which uses the outbox pattern for guaranteed
 * delivery), engagement events are fire-and-forget telemetry: the REST response
 * returns before Kafka acks. A missed event loses one data point; a blocked
 * REST call loses a user action.
 */
public record EngagementEvent(
        String eventId,
        String eventType,
        String streamId,
        String actorSubject,
        String targetType,
        String targetId,
        String category,
        OffsetDateTime occurredAt
) {
    /**
     * Factory for a VIEW event — the only event type in Phase A.
     *
     * @param streamId   UUID of the viewed stream
     * @param actor      JWT sub of the viewer
     * @param targetId   broadcaster username (CHANNEL target) or category name
     * @param category   category at time of view (nullable)
     */
    public static EngagementEvent viewed(UUID streamId, String actor,
                                         String targetId, String category) {
        return new EngagementEvent(
                UUID.randomUUID().toString(),
                "VIEW",
                streamId.toString(),
                actor,
                "CHANNEL",
                targetId,
                category,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
