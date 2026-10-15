package com.nexusgate.management.controller;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.UpdateUserStatusRequest;
import com.nexusgate.management.dto.UserResponse;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.OrganizationRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final OrganizationRegistrationService registrationService;

    public UserController(OrganizationRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody AddUserRequest request
    ) {
        return registrationService.addUserForAdmin(principal.userId(), request);
    }

    @PutMapping("/{userId}/status")
    public UserResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return registrationService.setMemberStatusForAdmin(principal.userId(), userId, request);
    }
}
