package com.nexusgate.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrganizationRegistrationRequest(
        @NotBlank
        @Size(max = 150)
        String organizationName,

        @NotBlank
        @Size(max = 63)
        @Pattern(regexp = "^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$")
        String organizationSlug,

        @NotBlank
        @Size(max = 150)
        String adminName,

        @NotBlank
        @Email
        @Size(max = 320)
        String adminEmail,

        @NotBlank
        @Size(min = 12, max = 72)
        String adminPassword
) {
}
