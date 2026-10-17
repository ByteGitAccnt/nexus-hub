package com.nexusgate.management.dto;

public record ApiKeyCreatedResponse(
        String secret,
        ApiKeyResponse apiKey
) {
}
