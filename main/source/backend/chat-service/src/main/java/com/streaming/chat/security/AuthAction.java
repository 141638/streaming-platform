package com.streaming.chat.security;

// PBAC-COMMON-CANDIDATE — extract to pbac-common in Phase 6.2

/**
 * Canonical authorization verbs for chat PBAC and JWT {@code ent} lines.
 *
 * <p>Persist policy rules using these wire values (stable strings), not ordinal
 * or enum names, so new actions can be added without rewriting historical rows.
 *
 * <p>Mirrors {@code com.streaming.auth.authorization.AuthAction} (chat subset).
 * Phase 6.2: extract to shared {@code pbac-common} library.
 */
public enum AuthAction {
    SEND("send"),
    READ("read"),
    READ_HISTORY("read_history"),
    MODERATE("moderate"),
    CREATE("create");

    private final String wireValue;

    AuthAction(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Value used inside JWT entitlement strings and persisted policy definitions.
     */
    public String wireValue() {
        return wireValue;
    }
}
