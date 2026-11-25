package com.nexusgate.management.dto;

import java.util.UUID;

public record ValidationResult(
                boolean valid,
                UUID apiKeyId,
                UUID organizationId,
                ValidServiceDetails service) {
}
