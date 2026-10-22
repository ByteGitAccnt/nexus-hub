package com.nexusgate.management.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidationRequest(
        @NotBlank
        String apiKey
) {
}
