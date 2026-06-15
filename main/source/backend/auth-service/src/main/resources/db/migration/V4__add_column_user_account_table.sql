-- Audit + soft-delete on user_account; many-to-many user ↔ catalog_role (roles from V3 seed).

ALTER TABLE auth.user_account
    ADD COLUMN created_by UUID REFERENCES auth.user_account (id) ON DELETE SET NULL,
    ADD COLUMN updated_by UUID REFERENCES auth.user_account (id) ON DELETE SET NULL,
    ADD COLUMN delete_flg BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN auth.user_account.created_by IS 'User id who created this row when known; null for migrated or system-created accounts.';
COMMENT ON COLUMN auth.user_account.updated_by IS 'User id who last updated this row when known.';
COMMENT ON COLUMN auth.user_account.delete_flg IS 'Soft delete; exclude from login and ordinary listings when true.';

-- One active row per username; allow reuse after soft-delete.
ALTER TABLE auth.user_account DROP CONSTRAINT uq_user_account_username;

CREATE UNIQUE INDEX uq_user_account_username_active
    ON auth.user_account (username)
    WHERE delete_flg = FALSE;
