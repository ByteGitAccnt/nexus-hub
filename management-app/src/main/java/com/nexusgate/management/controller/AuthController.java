package com.nexusgate.management.controller;

import com.nexusgate.management.dto.LoginRequest;
import com.nexusgate.management.dto.LoginResponse;
import com.nexusgate.management.dto.RefreshTokenRequest;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.AuthenticationService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Access JWT and refresh-token lifecycle")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Authenticates an active user and returns an access JWT and refresh token. Public endpoint.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(name = "Login", value = """
                            {"email":"ada@example.com","password":"<password>"}
                            """))))
    @ApiResponse(responseCode = "200", description = "Access and refresh tokens issued",
            content = @Content(schema = @Schema(implementation = LoginResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "accessToken": "<access-jwt>",
                              "tokenType": "Bearer",
                              "expiresIn": 900,
                              "refreshToken": "<opaque-refresh-token>",
                              "user": {
                                "id": "<user-uuid>",
                                "organizationId": "<organization-uuid>",
                                "role": "ADMIN"
                              }
                            }
                            """)))
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token", description = "Consumes a valid refresh token and returns a replacement token pair. Public endpoint.")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authenticationService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Log out", description = "Revokes active refresh tokens for the authenticated user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public void logout(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        authenticationService.logout(principal.userId());
    }
}
