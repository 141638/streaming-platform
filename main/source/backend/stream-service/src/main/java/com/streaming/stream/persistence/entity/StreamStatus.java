package com.streaming.stream.persistence.entity;

import org.springframework.lang.Nullable;

/**
 * Lifecycle states for a {@link StreamSessionEntity}.
 *
 * <p>Wire values are stored in the database column; use
 * {@link #wireValue()} and {@link #fromWireValue(String)} for conversion.
 */
public enum StreamStatus {
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    LIVE("live"),
    ENDED("ended"),
    CANCELLED("cancelled");

    private final String wireValue;

    StreamStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Value persisted in the database column. */
    public String wireValue() {
        return wireValue;
    }

    /** Resolve from a database value, or {@code null} when the input is blank. */
    @Nullable
    public static StreamStatus fromWireValue(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (StreamStatus s : values()) {
            if (s.wireValue.equals(value.trim())) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown stream status: " + value);
    }
}
