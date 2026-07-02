package com.streaming.chat.domain;

/**
 * Lifecycle status of a chat room.
 *
 * <p>A room is {@code ACTIVE} while its associated stream is live; it
 * transitions to {@code ARCHIVED} when the stream ends (or is cancelled).
 */
public enum RoomStatus {

    ACTIVE,
    ARCHIVED;

    /** Wire value used in API responses and database columns. */
    public String wireValue() {
        return name();
    }

    /** Parse a wire value back to an enum constant (case-insensitive). */
    public static RoomStatus fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RoomStatus cannot be null or blank");
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown RoomStatus: " + value);
        }
    }
}
