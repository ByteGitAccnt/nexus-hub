package com.nexusgate.nexus_gateway.Dto;

import java.util.UUID;

public record ValidationResult(
        boolean valid,
        UUID organizationId,
        ValidServiceDetails service
) {
}
