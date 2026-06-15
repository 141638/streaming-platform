package com.streaming.auth.authorization;

/**
 * Canonical authorization verbs for PBAC and JWT {@code ent} lines.
 * <p>
 * Persist policy rules in the database using these wire values (stable strings), not ordinal or
 * enum names, so new actions can be added without rewriting historical rows.
 */
public enum AuthAction {
    CREATE("create"),
    READ("read"),
    READ_SENSITIVE("read_sensitive"),
    UPDATE("update"),
    DELETE("delete"),
    ISSUE_KEY("issue_key"),
    VALIDATE_PUBLISH("validate_publish"),
    LIFECYCLE("lifecycle"),
    SEND("send"),
    READ_HISTORY("read_history"),
    MODERATE("moderate"),
    SUBSCRIBE_TOPICS("subscribe_topics"),
    MANAGE_OUTBOX("manage_outbox"),
    IMPERSONATE("impersonate");

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
