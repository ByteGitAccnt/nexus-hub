package com.nexusgate.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(
        name = "api_key_service_permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_api_key_service_permission",
                columnNames = {"api_key_id", "service_slug", "permission"}
        )
)
public class ApiKeyPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @Column(name = "service_slug", nullable = false, length = 63)
    private String serviceSlug;

    @Column(nullable = false, length = 64)
    private String permission;

    protected ApiKeyPermission() {
    }

    public ApiKeyPermission(String normalizedServiceSlug, String permission) {
        this.serviceSlug = normalizedServiceSlug;
        this.permission = permission;
    }

    void setApiKey(ApiKey apiKey) {
        this.apiKey = apiKey;
    }

    public UUID getId() {
        return id;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public String getServiceSlug() {
        return serviceSlug;
    }

    public String getPermission() {
        return permission;
    }
}
