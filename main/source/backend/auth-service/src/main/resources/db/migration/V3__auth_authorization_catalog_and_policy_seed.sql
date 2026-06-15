-- Fixed catalogs for JWT `attr` keys, platform roles, and tier codes (system-defined; not user-created).
-- Plus initial PBAC policy rows and role/service attachments (see docs/PBAC-AUTHORIZATION.md).

CREATE TABLE auth.catalog_subject_attribute (
    attribute_key   VARCHAR(64) PRIMARY KEY,
    json_value_kind VARCHAR(32)  NOT NULL,
    description     VARCHAR(512) NOT NULL,
    CONSTRAINT ck_catalog_subject_attribute_kind CHECK (
        json_value_kind IN ('STRING', 'BOOLEAN', 'STRING_ARRAY')
    )
);

CREATE TABLE auth.catalog_role (
    role_slug   VARCHAR(64) PRIMARY KEY,
    description VARCHAR(512) NOT NULL
);

CREATE TABLE auth.catalog_tier (
    tier_code    VARCHAR(32) PRIMARY KEY,
    display_order SMALLINT NOT NULL
);

COMMENT ON TABLE auth.catalog_subject_attribute IS 'Allowed keys for SubjectAttributes / JWT attr; issuance must use these keys only.';
COMMENT ON TABLE auth.catalog_role IS 'Platform role slugs; map to policies via auth.policy_attachment.';
COMMENT ON TABLE auth.catalog_tier IS 'Subscription tiers referenced by attr.tier at token issuance.';

-- --- Attribute keys (fixed set) ---
INSERT INTO auth.catalog_subject_attribute (attribute_key, json_value_kind, description) VALUES
    ('roles', 'STRING_ARRAY',
     'Platform role slugs aligned with auth.catalog_role; drives policy attachment resolution at login.'),
    ('tier', 'STRING',
     'Commercial / plan tier; must match auth.catalog_tier.tier_code when present.'),
    ('verified_streamer', 'BOOLEAN',
     'Workflow-verified streamer flag for additional policy rules at issuance.');

-- --- Roles ---
INSERT INTO auth.catalog_role (role_slug, description) VALUES
    ('viewer', 'Default consumer: read playback-oriented resources, public chat read, own notifications.'),
    ('streamer', 'Can create and run own stream sessions, publish keys, chat in linked rooms.'),
    ('moderator', 'Chat moderation actions on rooms (assignment enforced in application layer).'),
    ('admin', 'Platform administration; use separate token class and hardening in production.'),
    ('service', 'Marker for machine principals; prefer SERVICE_ACCOUNT policy_attachment entries.');

-- --- Tiers ---
INSERT INTO auth.catalog_tier (tier_code, display_order) VALUES
    ('FREE', 10),
    ('PRO', 20),
    ('ENTERPRISE', 30);

-- --- Policies (definition JSON is opaque to Flyway; shape is statement list for your materializer) ---
INSERT INTO auth.policy (policy_key, version, description, definition, definition_format, enabled)
VALUES
    (
        'policy.viewer.base',
        1,
        'Read playback metadata, public chat, own identity slice, own notification subscription.',
        $json$
        {"statements":[
          {"effect":"allow","resource":"media:playback:*","actions":["read"]},
          {"effect":"allow","resource":"chat:room:*","actions":["read"]},
          {"effect":"allow","resource":"notification:subscription:self","actions":["create","read","update","delete"]},
          {"effect":"allow","resource":"identity:user:self","actions":["read"]}
        ]}
        $json$,
        'JSON',
        TRUE
    ),
    (
        'policy.streamer.live',
        1,
        'Own stream sessions and publish keys; chat send; profile update.',
        $json$
        {"statements":[
          {"effect":"allow","resource":"stream:session:self","actions":["create","read","update","lifecycle","issue_key"]},
          {"effect":"allow","resource":"stream:publish-key:self","actions":["validate_publish"]},
          {"effect":"allow","resource":"chat:room:*","actions":["read"]},
          {"effect":"allow","resource":"chat:message:room:*","actions":["send"]},
          {"effect":"allow","resource":"identity:user:self","actions":["read","update"]}
        ]}
        $json$,
        'JSON',
        TRUE
    ),
    (
        'policy.moderator.chat',
        1,
        'Moderation and extended chat operations on rooms.',
        $json$
        {"statements":[
          {"effect":"allow","resource":"chat:moderation:room:*","actions":["moderate"]},
          {"effect":"allow","resource":"chat:message:room:*","actions":["read","read_history","send","delete"]},
          {"effect":"allow","resource":"chat:room:*","actions":["read"]}
        ]}
        $json$,
        'JSON',
        TRUE
    ),
    (
        'policy.service.srs-webhook',
        1,
        'SRS (or ingest) webhook: validate publish credentials only.',
        $json$
        {"statements":[
          {"effect":"allow","resource":"stream:publish-key:*","actions":["validate_publish"]}
        ]}
        $json$,
        'JSON',
        TRUE
    ),
    (
        'policy.admin.platform',
        1,
        'Wide admin surface; restrict attachments and protect with separate aud/MFA in production.',
        $json$
        {"statements":[
          {"effect":"allow","resource":"platform:admin:*","actions":["create","read","update","delete","read_sensitive","impersonate"]},
          {"effect":"allow","resource":"identity:user:*","actions":["read","read_sensitive","update"]}
        ]}
        $json$,
        'JSON',
        TRUE
    );

-- --- Role / service attachments (one policy row id per logical version) ---
INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'ROLE', 'viewer'
FROM auth.policy p
WHERE p.policy_key = 'policy.viewer.base' AND p.version = 1;

INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'ROLE', 'streamer'
FROM auth.policy p
WHERE p.policy_key = 'policy.streamer.live' AND p.version = 1;

-- Streamers also get viewer-class read paths (compose entitlements at issuance from all attached policies).
INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'ROLE', 'streamer'
FROM auth.policy p
WHERE p.policy_key = 'policy.viewer.base' AND p.version = 1;

INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'ROLE', 'moderator'
FROM auth.policy p
WHERE p.policy_key = 'policy.moderator.chat' AND p.version = 1;

INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'ROLE', 'admin'
FROM auth.policy p
WHERE p.policy_key = 'policy.admin.platform' AND p.version = 1;

INSERT INTO auth.policy_attachment (policy_id, principal_type, principal_subject)
SELECT p.id, 'SERVICE_ACCOUNT', 'svc:srs-webhook'
FROM auth.policy p
WHERE p.policy_key = 'policy.service.srs-webhook' AND p.version = 1;
