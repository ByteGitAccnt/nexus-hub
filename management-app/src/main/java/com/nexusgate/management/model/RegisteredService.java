package com.nexusgate.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "registered_services",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_registered_services_organization_name",
                columnNames = {"organization_id", "name"}
        )
)
public class RegisteredService {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "organization_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_registered_services_organization")
    )
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Column(name = "base_path", nullable = false, length = 255)
    private String basePath;

    @Column(name = "target_path", length = 2048)
    private String targetPath;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 20)
    private ServiceAuthMode authMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegisteredService() {
    }

    public RegisteredService(
            Organization organization,
            String name,
            String targetUrl,
            String basePath,
            String targetPath,
            ServiceAuthMode authMode
    ) {
        this.organization = organization;
        this.name = name;
        this.targetUrl = targetUrl;
        this.basePath = basePath;
        this.targetPath = targetPath;
        this.authMode = authMode;
    }

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
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

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public boolean isActive() {
        return active;
    }

    public ServiceAuthMode getAuthMode() {
        return authMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
