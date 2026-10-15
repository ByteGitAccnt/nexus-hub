package com.nexusgate.management.dto;

import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record OrganizationRegistrationResponse(
        UUID id,
        String name,
        String slug,
        OrganizationStatus status,
        Instant createdAt,
        AdminResponse admin
) {
    public record AdminResponse(
            UUID id,
            String name,
            String email,
            UserRole role,
            UserStatus status,
            Instant createdAt
    ) {
    }
}
