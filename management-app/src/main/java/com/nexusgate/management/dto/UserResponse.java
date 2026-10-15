package com.nexusgate.management.dto;

import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID organizationId,
        String name,
        String email,
        UserRole role,
        UserStatus status,
        Instant createdAt
) {
}
