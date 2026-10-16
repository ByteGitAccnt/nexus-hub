package com.nexusgate.management.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateServiceStatusRequest(
        @NotNull Boolean active
) {
}
