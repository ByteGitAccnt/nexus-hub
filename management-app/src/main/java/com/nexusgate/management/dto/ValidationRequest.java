package com.nexusgate.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ValidationRequest(
        @NotBlank
        @Size(max = 128)
        String apiKey,
        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "^/(?!/)[^?#]*$")
        String reqPath
) {
}
