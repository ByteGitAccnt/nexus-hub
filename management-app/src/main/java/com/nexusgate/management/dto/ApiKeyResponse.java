package com.nexusgate.management.dto;

import com.nexusgate.management.model.ApiKeyStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        UUID organizationId,
        String name,
        String lookupPrefix,
        ApiKeyStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        List<ApiKeyServicePermissionResponse> permissions
) {
}
