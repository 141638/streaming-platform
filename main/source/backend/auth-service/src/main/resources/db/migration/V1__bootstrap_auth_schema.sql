-- Auth bounded context — no cross-schema FKs.
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.user_account (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_account_username UNIQUE (username)
);
