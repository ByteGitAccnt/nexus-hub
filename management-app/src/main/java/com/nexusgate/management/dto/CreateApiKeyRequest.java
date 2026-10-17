package com.nexusgate.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateApiKeyRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotEmpty
        @Size(max = 100)
        List<@NotNull UUID> serviceIds,

        @Future
        Instant expiresAt
) {
}
