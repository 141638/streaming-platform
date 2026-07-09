package com.streaming.chat.domain;

/**
 * Categorizes chat messages for rendering and persistence.
 *
 * <p>{@code SUPER_CHAT} and {@code SYSTEM} are scaffolded in Phase 3.3 —
 * the schema supports them and the write path accepts them, but the
 * producer side (payments for superchat, stream events for system messages)
 * is deferred to later phases.
 */
public enum MessageType {
    NORMAL,
    SUPER_CHAT,
    SYSTEM;

    /** Returns the lowercase wire value for JSON serialization. */
    public String wireValue() {
        return name();
    }

    /** Resolve a wire value back to the enum constant. */
    public static MessageType fromWireValue(String wire) {
        for (MessageType t : values()) {
            if (t.wireValue().equals(wire)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + wire);
    }
}
