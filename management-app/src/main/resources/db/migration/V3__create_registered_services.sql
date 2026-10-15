CREATE TABLE registered_services (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    target_url VARCHAR(2048) NOT NULL,
    base_path VARCHAR(255) NOT NULL,
    target_path VARCHAR(2048),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    auth_mode VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_registered_services_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT uk_registered_services_organization_name
        UNIQUE (organization_id, name),
    CONSTRAINT ck_registered_services_auth_mode
        CHECK (auth_mode IN ('PUBLIC', 'JWT', 'API_KEY'))
);

CREATE INDEX ix_registered_services_organization_id
    ON registered_services (organization_id);
