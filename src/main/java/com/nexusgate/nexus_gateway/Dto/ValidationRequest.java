package com.nexusgate.nexus_gateway.Dto;

import jakarta.validation.constraints.NotBlank;

public record ValidationRequest(

        @NotBlank
        String apiKey,
        @NotBlank
        String lookupPrefix,
        @NotBlank
        String reqPath
) {
}
