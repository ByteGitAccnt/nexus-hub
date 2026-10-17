package com.nexusgate.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(name = "lookup_prefix", nullable = false, length = 32, unique = true)
    private String lookupPrefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    @OneToMany(mappedBy = "apiKey")
    private List<ApiKeyPermission> permissions = new ArrayList<>();

    protected ApiKey() {
    }

    public ApiKey(
            Organization organization,
            String name,
            String keyHash,
            String lookupPrefix,
            Instant expiresAt
    ) {
        this.organization = organization;
        this.name = name;
        this.keyHash = keyHash;
        this.lookupPrefix = lookupPrefix;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void addPermission(ApiKeyPermission permission) {
        permissions.add(permission);
        permission.setApiKey(this);
    }

    public void revoke() {
        if (status != ApiKeyStatus.REVOKED) {
            status = ApiKeyStatus.REVOKED;
            revokedAt = Instant.now();
        }
    }

    public void setInactive() {
        if (status != ApiKeyStatus.REVOKED) {
            status = ApiKeyStatus.INACTIVE;
        }
    }

    public void setActive(boolean active, Instant now) {
        if (status == ApiKeyStatus.REVOKED) {
            throw new IllegalStateException("A revoked API key cannot be reactivated");
        }
        if (active && expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalStateException("An expired API key cannot be reactivated");
        }
        status = active ? ApiKeyStatus.ACTIVE : ApiKeyStatus.INACTIVE;
    }

    public boolean isUsableAt(Instant now) {
        return status == ApiKeyStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLookupPrefix() {
        return lookupPrefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public ApiKeyStatus getStatus() {
        return status;
    }

    public List<ApiKeyPermission> getPermissions() {
        return List.copyOf(permissions);
    }
}
