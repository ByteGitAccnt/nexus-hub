package com.nexusgate.management.dto;

import com.nexusgate.management.model.ApiKeyPermissionType;

import java.util.UUID;

public record ApiKeyServicePermissionResponse(
        UUID serviceId,
        String serviceName,
        ApiKeyPermissionType permission
) {
}
