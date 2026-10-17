package com.nexusgate.management.controller;

import com.nexusgate.management.dto.ApiKeyCreatedResponse;
import com.nexusgate.management.dto.ApiKeyResponse;
import com.nexusgate.management.dto.CreateApiKeyRequest;
import com.nexusgate.management.dto.UpdateApiKeyStatusRequest;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.ApiKeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@Tag(name = "API keys", description = "Organization-scoped API-key lifecycle and service permissions")
public class ApiKeyController {

    private final ApiKeyManagementService apiKeyManagementService;

    public ApiKeyController(ApiKeyManagementService apiKeyManagementService) {
        this.apiKeyManagementService = apiKeyManagementService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an API key", description = "ADMIN only. Returns the generated secret once; only its hash is stored.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Key for one service", value = """
                            {
                              "name": "Mobile App Key",
                              "serviceIds": ["<registered-service-uuid>"],
                              "expiresAt": "2027-01-01T00:00:00Z"
                            }
                            """))))
    @ApiResponse(responseCode = "201", description = "Key created; secret is shown only in this response",
            content = @Content(schema = @Schema(implementation = ApiKeyCreatedResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "secret": "ng_live_<one-time-secret>",
                              "apiKey": {
                                "id": "<api-key-uuid>",
                                "organizationId": "<organization-uuid>",
                                "name": "Mobile App Key",
                                "lookupPrefix": "ng_live_<non-secret-prefix>",
                                "status": "ACTIVE",
                                "createdAt": "2026-10-17T09:30:00Z",
                                "expiresAt": "2027-01-01T00:00:00Z",
                                "revokedAt": null,
                                "permissions": [{
                                  "serviceId": "<registered-service-uuid>",
                                  "serviceName": "Orders",
                                  "permission": "INVOKE"
                                }]
                              }
                            }
                            """)))
    public ApiKeyCreatedResponse create(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeyManagementService.create(principal.userId(), request);
    }

    @GetMapping
    @Operation(summary = "List API keys", description = "ADMIN only. Returns safe metadata without the key secret or hash.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public List<ApiKeyResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return apiKeyManagementService.list(principal.userId());
    }

    @GetMapping("/{keyId}")
    @Operation(summary = "Get API-key metadata", description = "ADMIN only. Returns safe metadata without the key secret or hash.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiKeyResponse get(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId) {
        return apiKeyManagementService.get(principal.userId(), keyId);
    }

    @PatchMapping("/{keyId}/status")
    @Operation(summary = "Activate or deactivate an API key", description = "ADMIN only. Revoked keys cannot be reactivated; expired keys cannot be activated.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Deactivate API key", value = "{\"active\":false}"))))
    public ApiKeyResponse setStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateApiKeyStatusRequest request) {
        return apiKeyManagementService.setActive(principal.userId(), keyId, request.active());
    }

    @PostMapping("/{keyId}/revoke")
    @Operation(summary = "Permanently revoke an API key", description = "ADMIN only. Revocation is permanent and repeatable.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiKeyResponse revoke(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID keyId) {
        return apiKeyManagementService.revoke(principal.userId(), keyId);
    }
}
