package com.nexusgate.management.controller;

import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.service.OrganizationRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organization", description = "Organization registration")
public class OrganizationController {

    private final OrganizationRegistrationService registrationService;

    public OrganizationController(OrganizationRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register an organization",
            description = "Creates an organization and its initial administrator. Public endpoint.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Registration", value = """
                            {
                              "organizationName": "Acme Systems",
                              "organizationSlug": "acme-systems",
                              "adminName": "Ada Admin",
                              "adminEmail": "ada@example.com",
                              "adminPassword": "<strong-password>"
                            }
                            """))))
    @ApiResponse(responseCode = "201", description = "Organization and initial administrator created",
            content = @Content(schema = @Schema(implementation = OrganizationRegistrationResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "id": "<organization-uuid>",
                              "name": "Acme Systems",
                              "slug": "acme-systems",
                              "status": "ACTIVE",
                              "createdAt": "2026-10-17T09:00:00Z",
                              "admin": {
                                "id": "<admin-user-uuid>",
                                "name": "Ada Admin",
                                "email": "ada@example.com",
                                "role": "ADMIN",
                                "status": "ACTIVE",
                                "createdAt": "2026-10-17T09:00:00Z"
                              }
                            }
                            """)))
    public OrganizationRegistrationResponse register(@Valid @RequestBody OrganizationRegistrationRequest request) {
        return registrationService.register(request);
    }
}
