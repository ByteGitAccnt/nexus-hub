package com.nexusgate.management.security;

import com.nexusgate.management.model.UserRole;

import java.util.UUID;

public record AuthenticatedUserPrincipal(
        UUID userId,
        UUID organizationId,
        UserRole role
) {
}
