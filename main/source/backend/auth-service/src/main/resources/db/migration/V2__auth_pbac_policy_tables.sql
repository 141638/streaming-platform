-- PBAC policy store and optional entitlement materialization cache (see docs/PBAC-AUTHORIZATION.md).
-- All objects live in schema auth; user attachments reference auth.user_account where applicable.

-- Logical policy document: stable policy_key + monotonic version rows (attach to a specific row id).
CREATE TABLE auth.policy (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_key         VARCHAR(128) NOT NULL,
    version            INTEGER      NOT NULL DEFAULT 1,
    description        VARCHAR(512),
    definition         TEXT         NOT NULL,
    definition_format  VARCHAR(32)  NOT NULL DEFAULT 'JSON',
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_key_version UNIQUE (policy_key, version),
    CONSTRAINT ck_policy_definition_format CHECK (definition_format IN ('JSON', 'YAML', 'REGO_BUNDLE'))
);

CREATE INDEX ix_policy_policy_key ON auth.policy (policy_key);
CREATE INDEX ix_policy_enabled ON auth.policy (enabled) WHERE enabled = TRUE;

COMMENT ON TABLE auth.policy IS 'Named PBAC policy versions; definition holds serialized rules (JSON/YAML) or bundle reference.';
COMMENT ON COLUMN auth.policy.policy_key IS 'Stable id e.g. policy.streamer.live; pair with version for immutability.';
COMMENT ON COLUMN auth.policy.definition IS 'Opaque to Flyway: statements, resource patterns, actions, optional conditions.';

-- Bind policy rows to principals: users (FK), or role/group/service identifiers as strings.
CREATE TABLE auth.policy_attachment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id           UUID         NOT NULL REFERENCES auth.policy (id) ON DELETE CASCADE,
    principal_type      VARCHAR(32)  NOT NULL,
    principal_user_id   UUID         REFERENCES auth.user_account (id) ON DELETE CASCADE,
    principal_subject   VARCHAR(256),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_attachment_principal_type CHECK (
        principal_type IN ('USER', 'ROLE', 'GROUP', 'SERVICE_ACCOUNT')
    ),
    CONSTRAINT ck_policy_attachment_principal_target CHECK (
        (principal_type = 'USER' AND principal_user_id IS NOT NULL)
        OR (
            principal_type <> 'USER'
            AND principal_subject IS NOT NULL
            AND length(trim(principal_subject)) > 0
            AND principal_user_id IS NULL
        )
    )
);

CREATE UNIQUE INDEX uq_policy_attachment_user
    ON auth.policy_attachment (policy_id, principal_user_id)
    WHERE principal_type = 'USER';

CREATE UNIQUE INDEX uq_policy_attachment_principal_subject
    ON auth.policy_attachment (policy_id, principal_type, principal_subject)
    WHERE principal_type <> 'USER';

CREATE INDEX ix_policy_attachment_policy ON auth.policy_attachment (policy_id);
CREATE INDEX ix_policy_attachment_principal_user ON auth.policy_attachment (principal_user_id)
    WHERE principal_user_id IS NOT NULL;
CREATE INDEX ix_policy_attachment_principal_subject ON auth.policy_attachment (principal_type, principal_subject)
    WHERE principal_subject IS NOT NULL;

COMMENT ON TABLE auth.policy_attachment IS 'Maps auth.policy rows to users, roles, groups, or service accounts.';
COMMENT ON COLUMN auth.policy_attachment.principal_subject IS 'Role/group/service slug when principal_type <> USER e.g. svc:srs-webhook.';

-- Optional cache row for JWT issuance hot path / refresh (TTL enforced in application layer).
CREATE TABLE auth.entitlement_materialization_cache (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_claim                VARCHAR(256) NOT NULL,
    policy_version               VARCHAR(128) NOT NULL,
    entitlement_grammar_version  INTEGER      NOT NULL DEFAULT 1,
    ent_lines                    TEXT[]       NOT NULL,
    expires_at                   TIMESTAMPTZ  NOT NULL,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entitlement_cache_subject_pv UNIQUE (subject_claim, policy_version)
);

CREATE INDEX ix_entitlement_cache_subject_expiry
    ON auth.entitlement_materialization_cache (subject_claim, expires_at);
CREATE INDEX ix_entitlement_cache_expires_at ON auth.entitlement_materialization_cache (expires_at);

COMMENT ON TABLE auth.entitlement_materialization_cache IS 'Optional snapshot of JWT ent claims for subject + pv; invalidate or TTL when catalog changes.';
COMMENT ON COLUMN auth.entitlement_materialization_cache.policy_version IS 'JWT pv claim materialization fingerprint e.g. timestamp or semver.';
COMMENT ON COLUMN auth.entitlement_materialization_cache.ent_lines IS 'Compact entitlement lines consumed as JWT ent array.';
