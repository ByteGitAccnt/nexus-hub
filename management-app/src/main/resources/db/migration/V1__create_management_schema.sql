CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(63) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_organizations_slug UNIQUE (slug),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_organizations_slug_normalized CHECK (slug = lower(slug))
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX ix_users_organization_id ON users (organization_id);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    name VARCHAR(100) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    lookup_prefix VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_api_keys_key_hash UNIQUE (key_hash),
    CONSTRAINT uk_api_keys_lookup_prefix UNIQUE (lookup_prefix),
    CONSTRAINT ck_api_keys_expiration CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX ix_api_keys_organization_id ON api_keys (organization_id);

CREATE TABLE api_key_service_permissions (
    id UUID PRIMARY KEY,
    api_key_id UUID NOT NULL REFERENCES api_keys (id) ON DELETE CASCADE,
    service_slug VARCHAR(63) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    CONSTRAINT uk_api_key_service_permission
        UNIQUE (api_key_id, service_slug, permission),
    CONSTRAINT ck_api_key_permission_service_slug_normalized
        CHECK (service_slug = lower(service_slug))
);

CREATE INDEX ix_api_key_service_permissions_service_slug
    ON api_key_service_permissions (service_slug);
