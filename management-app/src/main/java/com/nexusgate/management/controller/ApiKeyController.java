package com.nexusgate.management.controller;

import com.nexusgate.management.dto.ApiKeyCreatedResponse;
import com.nexusgate.management.dto.ApiKeyResponse;
import com.nexusgate.management.dto.CreateApiKeyRequest;
import com.nexusgate.management.dto.UpdateApiKeyStatusRequest;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.ApiKeyManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyManagementService;

    public ApiKeyController(ApiKeyManagementService apiKeyManagementService) {
        this.apiKeyManagementService = apiKeyManagementService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreatedResponse create(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeyManagementService.create(principal.userId(), request);
    }

    @GetMapping
    public List<ApiKeyResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return apiKeyManagementService.list(principal.userId());
    }

    @GetMapping("/{keyId}")
    public ApiKeyResponse get(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId) {
        return apiKeyManagementService.get(principal.userId(), keyId);
    }

    @PatchMapping("/{keyId}/status")
    public ApiKeyResponse setStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateApiKeyStatusRequest request) {
        return apiKeyManagementService.setActive(principal.userId(), keyId, request.active());
    }

    @PostMapping("/{keyId}/revoke")
    public ApiKeyResponse revoke(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId) {
        return apiKeyManagementService.revoke(principal.userId(), keyId);
    }
}
