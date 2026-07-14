package com.streaming.notification.domain;

/**
 * Categorizes notifications for rendering and routing.
 *
 * <p>{@link #STREAM_LIVE} and {@link #STREAM_ENDED} are produced by the
 * {@code StreamControlListener} from Kafka stream events.
 * {@link #CHAT_MENTION} and {@link #CHAT_MODERATION} will be produced by
 * the Wave 2 {@code ModerationListener} and future mention notification
 * pipeline.
 */
public enum NotificationCategory {
    STREAM_LIVE,
    STREAM_ENDED,
    CHAT_MENTION,
    CHAT_MODERATION,
    SYSTEM;

    /** Returns the wire value for database storage and JSON serialization. */
    public String wireValue() {
        return name();
    }

    /** Resolve a wire value back to the enum constant. */
    public static NotificationCategory fromWireValue(String wire) {
        for (NotificationCategory c : values()) {
            if (c.wireValue().equals(wire)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown notification category: " + wire);
    }
}
