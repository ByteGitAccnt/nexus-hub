ALTER TABLE api_keys
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

UPDATE api_keys
SET status = CASE WHEN revoked_at IS NULL THEN 'ACTIVE' ELSE 'REVOKED' END;

ALTER TABLE api_keys
    ADD CONSTRAINT ck_api_keys_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    ADD CONSTRAINT ck_api_keys_revocation_state
        CHECK ((status = 'REVOKED' AND revoked_at IS NOT NULL)
            OR (status <> 'REVOKED' AND revoked_at IS NULL)),
    ADD CONSTRAINT uk_api_keys_id_organization
        UNIQUE (id, organization_id);

ALTER TABLE registered_services
    ADD CONSTRAINT uk_registered_services_id_organization
        UNIQUE (id, organization_id);

ALTER TABLE api_key_service_permissions
    RENAME TO api_key_service_permissions_legacy;

ALTER TABLE api_key_service_permissions_legacy
    RENAME CONSTRAINT uk_api_key_service_permission
        TO uk_api_key_service_permission_legacy;

ALTER INDEX ix_api_key_service_permissions_service_slug
    RENAME TO ix_api_key_service_permissions_legacy_slug;

CREATE TABLE api_key_service_permissions (
    id UUID PRIMARY KEY,
    api_key_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    service_id UUID NOT NULL,
    permission VARCHAR(64) NOT NULL,
    CONSTRAINT fk_api_key_service_permissions_key_organization
        FOREIGN KEY (api_key_id, organization_id)
        REFERENCES api_keys (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_api_key_service_permissions_service_organization
        FOREIGN KEY (service_id, organization_id)
        REFERENCES registered_services (id, organization_id),
    CONSTRAINT uk_api_key_service_permission
        UNIQUE (api_key_id, service_id, permission),
    CONSTRAINT ck_api_key_service_permissions_permission
        CHECK (permission IN ('INVOKE'))
);

CREATE INDEX ix_api_key_service_permissions_service_id
    ON api_key_service_permissions (service_id);

CREATE INDEX ix_api_key_service_permissions_organization_id
    ON api_key_service_permissions (organization_id);
