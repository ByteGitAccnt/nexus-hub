package com.nexusgate.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "api_key_service_permissions")
public class ApiKeyPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_key_id", nullable = false)
    private ApiKey apiKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private RegisteredService service;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private ApiKeyPermissionType permission;

    protected ApiKeyPermission() {
    }

    public ApiKeyPermission(RegisteredService service, ApiKeyPermissionType permission) {
        this.service = service;
        this.organization = service.getOrganization();
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

    public RegisteredService getService() {
        return service;
    }

    public Organization getOrganization() {
        return organization;
    }

    public ApiKeyPermissionType getPermission() {
        return permission;
    }
}
