package com.nexusgate.management.controller;

import com.nexusgate.management.dto.CreateServiceRequest;
import com.nexusgate.management.dto.RegisteredServiceResponse;
import com.nexusgate.management.security.AuthenticatedUserPrincipal;
import com.nexusgate.management.service.ServiceRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceRegistrationService serviceRegistrationService;

    public ServiceController(ServiceRegistrationService serviceRegistrationService) {
        this.serviceRegistrationService = serviceRegistrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredServiceResponse register(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody CreateServiceRequest request
    ) {
        return serviceRegistrationService.register(principal.userId(), request);
    }

    @GetMapping
    public List<RegisteredServiceResponse> list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return serviceRegistrationService.listForOrganization(principal.organizationId());
    }

    @GetMapping("/{serviceId}")
    public RegisteredServiceResponse get(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID serviceId
    ) {
        return serviceRegistrationService.getForOrganization(principal.organizationId(), serviceId);
    }
}
