-- Add per-user attribute columns for JWT attr claims.
-- catalog_subject_attribute defines the schema; these columns hold the per-user values.

ALTER TABLE auth.user_account
    ADD COLUMN tier_code VARCHAR(32),
    ADD COLUMN verified_streamer BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth.user_account
    ADD CONSTRAINT fk_user_account_tier_code
        FOREIGN KEY (tier_code) REFERENCES auth.catalog_tier(tier_code);

COMMENT ON COLUMN auth.user_account.tier_code IS 'Subscription tier FK to auth.catalog_tier; emitted as jwt attr.tier';
COMMENT ON COLUMN auth.user_account.verified_streamer IS 'Workflow-verified streamer flag; emitted as jwt attr.verified_streamer';
