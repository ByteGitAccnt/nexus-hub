package com.nexusgate.management.controller;

import com.nexusgate.management.dto.CreateServiceRequest;
import com.nexusgate.management.dto.RegisteredServiceResponse;
import com.nexusgate.management.dto.UpdateServiceStatusRequest;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.ServiceRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
@Tag(name = "Registered services", description = "Organization-scoped service configuration")
public class ServiceController {

    private final ServiceRegistrationService serviceRegistrationService;

    public ServiceController(ServiceRegistrationService serviceRegistrationService) {
        this.serviceRegistrationService = serviceRegistrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a service", description = "ADMIN only. Stores service configuration; target URL validation is syntax-only.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "API-key protected service", value = """
                            {
                              "name": "Orders",
                              "targetUrl": "https://orders.example.com/api",
                              "basePath": "/orders",
                              "targetPath": "/api",
                              "authMode": "API_KEY"
                            }
                            """))))
    public RegisteredServiceResponse register(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody CreateServiceRequest request
    ) {
        return serviceRegistrationService.register(principal.userId(), request);
    }

    @GetMapping
    @Operation(summary = "List services", description = "Lists services belonging to the authenticated user's organization. ADMIN and MEMBER.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public List<RegisteredServiceResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return serviceRegistrationService.listForOrganization(principal.organizationId());
    }

    @GetMapping("/{serviceId}")
    @Operation(summary = "Get a service", description = "Gets a service in the authenticated user's organization. ADMIN and MEMBER.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public RegisteredServiceResponse get(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID serviceId
    ) {
        return serviceRegistrationService.getForOrganization(principal.organizationId(), serviceId);
    }

    @PutMapping("/{serviceId}")
    @Operation(summary = "Replace service configuration", description = "ADMIN only. Replaces the service configuration; this is not a partial update.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public RegisteredServiceResponse update(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID serviceId,
            @Valid @RequestBody CreateServiceRequest request
    ) {
        return serviceRegistrationService.update(principal.userId(), serviceId, request);
    }

    @PatchMapping("/{serviceId}/status")
    @Operation(summary = "Change service status", description = "ADMIN only. Activates or deactivates a service without deleting it.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Deactivate service", value = "{\"active\":false}"))))
    public RegisteredServiceResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID serviceId,
            @Valid @RequestBody UpdateServiceStatusRequest request
    ) {
        return serviceRegistrationService.setActive(principal.userId(), serviceId, request.active());
    }
}
