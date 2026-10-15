package com.nexusgate.management.dto;

import com.nexusgate.management.model.UserRole;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        AuthenticatedUser user
) {
    public record AuthenticatedUser(
            UUID id,
            UUID organizationId,
            UserRole role
    ) {
    }
}
