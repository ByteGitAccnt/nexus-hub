package com.nexusgate.management.dto;

import com.nexusgate.management.model.ServiceAuthMode;

import java.time.Instant;
import java.util.UUID;

public record RegisteredServiceResponse(
        UUID id,
        UUID organizationId,
        String name,
        String targetUrl,
        String basePath,
        String targetPath,
        boolean active,
        ServiceAuthMode authMode,
        Instant createdAt,
        Instant updatedAt
) {
}
