package com.streaming.stream.persistence.entity;

import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Lifecycle states for a {@link StreamSessionEntity}.
 *
 * <p>Wire values are stored in the database column; use
 * {@link #wireValue()} and {@link #fromWireValue(String)} for conversion.
 *
 * <p>State machine: each status defines which target states are reachable
 * via {@link #allowedTransitions()}. The entity's {@code transitionTo()} method
 * enforces these rules at the domain layer.
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

    /**
     * Valid target states reachable from this status.
     *
     * <p>DRAFT and SCHEDULED are <em>planned</em> states — they can be cancelled.
     * LIVE is an <em>active</em> state — it can only be ended.
     * ENDED and CANCELLED are terminal.
     */
    public Set<StreamStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT     -> Set.of(LIVE, CANCELLED);
            case SCHEDULED -> Set.of(CANCELLED, DRAFT); // CANCELLED = user cancel; DRAFT = go-live action resets to draft
            case LIVE      -> Set.of(ENDED);
            case ENDED, CANCELLED -> Set.of();
        };
    }

    /** Convenience check for {@link #allowedTransitions()}. */
    public boolean canTransitionTo(StreamStatus target) {
        return allowedTransitions().contains(target);
    }
}
