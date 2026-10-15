package com.nexusgate.management.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull
        Boolean active
) {
}
