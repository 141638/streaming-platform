package com.streaming.auth.authorization;

/**
 * Matches JWT {@code ver} claim: grammar version for compact {@code ent} entitlement lines.
 */
public enum EntitlementGrammarVersion {
    /**
     * {@code allow <resourcePattern> <action> [<action> ...]}
     */
    V1(1);

    private final int numericClaim;

    EntitlementGrammarVersion(int numericClaim) {
        this.numericClaim = numericClaim;
    }

    public int numericClaim() {
        return numericClaim;
    }

    /** Default grammar issued with new tokens. */
    public static EntitlementGrammarVersion current() {
        return V1;
    }
}
