CREATE TABLE auth.user_account_role (
    user_account_id UUID        NOT NULL REFERENCES auth.user_account (id) ON DELETE CASCADE,
    role_slug       VARCHAR(64) NOT NULL REFERENCES auth.catalog_role (role_slug),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by      UUID        REFERENCES auth.user_account (id) ON DELETE SET NULL,
    CONSTRAINT pk_user_account_role PRIMARY KEY (user_account_id, role_slug)
);

CREATE INDEX ix_user_account_role_role_slug ON auth.user_account_role (role_slug);

COMMENT ON TABLE auth.user_account_role IS 'Assigns seeded catalog roles (e.g. viewer, streamer) to user accounts.';
COMMENT ON COLUMN auth.user_account_role.granted_by IS 'User or admin principal who granted the role when tracked.';
