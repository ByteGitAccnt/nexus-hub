package com.nexusgate.nexus_gateway.Dto;

import java.util.UUID;

public record ValidationResult(
        boolean valid,
        UUID apiKeyId,
        UUID organizationId,
        ValidServiceDetails service
) {
}
