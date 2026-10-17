package com.nexusgate.management.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateApiKeyStatusRequest(
        @NotNull Boolean active
) {
}
