-- Opaque refresh tokens: store hash only; rotation + revoke family on reuse of revoked token.

CREATE TABLE auth.refresh_token (
    id                UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_account_id   UUID         NOT NULL   REFERENCES auth.user_account (id) ON DELETE CASCADE,
    token_hash        BYTEA        NOT NULL,
    token_family_id   UUID         NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL   DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_token_user ON auth.refresh_token (user_account_id);
CREATE INDEX ix_refresh_token_family ON auth.refresh_token (token_family_id);
CREATE INDEX ix_refresh_token_family_active
    ON auth.refresh_token (token_family_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE auth.refresh_token IS 'Hashed rotating refresh secrets; plaintext only at mint time.';
COMMENT ON COLUMN auth.refresh_token.token_family_id IS 'Token rotation chain; revoke all active rows when reuse attack detected.';
