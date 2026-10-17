package com.nexusgate.management.controller;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.UpdateUserStatusRequest;
import com.nexusgate.management.dto.UserResponse;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.OrganizationRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Organization-scoped user administration")
public class UserController {

    private final OrganizationRegistrationService registrationService;

    public UserController(OrganizationRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    @Operation(summary = "List organization users", description = "ADMIN only. Lists users belonging to the authenticated administrator's organization.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public List<UserResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return registrationService.listUsersForAdmin(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a member", description = "ADMIN only. Creates a MEMBER in the administrator's organization.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "New member", value = """
                            {"name":"Morgan Member","email":"morgan@example.com","password":"<strong-password>"}
                            """))))
    public UserResponse create(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody AddUserRequest request
    ) {
        return registrationService.addUserForAdmin(principal.userId(), request);
    }

    @PutMapping("/{userId}/status")
    @Operation(summary = "Change member status", description = "ADMIN only. Activates or disables a same-organization MEMBER.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Deactivate member", value = "{\"active\":false}"))))
    public UserResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return registrationService.setMemberStatusForAdmin(principal.userId(), userId, request);
    }
}
