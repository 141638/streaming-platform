-- Register username as a JWT attr claim key so it resolves at token issuance.
-- user_account.username is NOT NULL, so the resolved value is always present.
INSERT INTO auth.catalog_subject_attribute (attribute_key, json_value_kind, description) VALUES
    ('username', 'STRING',
     'Public channel handle; mutable convenience claim (identity authority remains sub/UUID).');
