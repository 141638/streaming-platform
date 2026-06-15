-- Add email column to user_account for password-reset flow.
-- Uses a filtered unique index to allow re-use after soft-delete (same pattern as username).

ALTER TABLE auth.user_account
    ADD COLUMN email VARCHAR(320) NOT NULL DEFAULT '';

COMMENT ON COLUMN auth.user_account.email IS 'User email for password reset and notifications.';

-- One active row per email; allow reuse after soft-delete.
CREATE UNIQUE INDEX uq_user_account_email_active
    ON auth.user_account (email)
    WHERE delete_flg = FALSE;
