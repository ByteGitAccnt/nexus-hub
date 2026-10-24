package com.nexusgate.management.dto;

import java.util.List;
import java.util.UUID;

public record ValidationResult(
        boolean valid,
        UUID organizationId,
        ValidServiceDetails services
) {
}
