package com.nexusgate.management.service;

import com.nexusgate.management.dto.CreateServiceRequest;
import com.nexusgate.management.dto.RegisteredServiceResponse;
import com.nexusgate.management.exception.ResourceConflictException;
import com.nexusgate.management.exception.ResourceNotFoundException;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

@Service
public class ServiceRegistrationService {

    private final UserRepository userRepository;
    private final RegisteredServiceRepository registeredServiceRepository;

    public ServiceRegistrationService(
            UserRepository userRepository,
            RegisteredServiceRepository registeredServiceRepository
    ) {
        this.userRepository = userRepository;
        this.registeredServiceRepository = registeredServiceRepository;
    }

    @Transactional
    public RegisteredServiceResponse register(UUID actorUserId, CreateServiceRequest request) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new AccessDeniedException("Active organization administrator required"));
        Organization organization = actor.getOrganization();
        if (actor.getRole() != UserRole.ADMIN
                || actor.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new AccessDeniedException("Active organization administrator required");
        }

        RegisteredService service = new RegisteredService(
                organization,
                request.name().trim(),
                request.targetUrl().trim(),
                request.basePath().trim(),
                request.targetPath() == null || request.targetPath().isBlank()
                        ? null
                        : request.targetPath().trim(),
                request.authMode()
        );
        try {
            return toResponse(registeredServiceRepository.saveAndFlush(service));
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("A service with this name is already registered");
        }
    }

    @Transactional(readOnly = true)
    public List<RegisteredServiceResponse> listForOrganization(UUID organizationId) {
        return registeredServiceRepository.findAllByOrganizationId(organizationId).stream()
                .map(ServiceRegistrationService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RegisteredServiceResponse getForOrganization(UUID organizationId, UUID serviceId) {
        RegisteredService service = registeredServiceRepository
                .findByIdAndOrganizationId(serviceId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));
        return toResponse(service);
    }

    private static RegisteredServiceResponse toResponse(RegisteredService service) {
        return new RegisteredServiceResponse(
                service.getId(),
                service.getOrganization().getId(),
                service.getName(),
                service.getTargetUrl(),
                service.getBasePath(),
                service.getTargetPath(),
                service.isActive(),
                service.getAuthMode(),
                service.getCreatedAt(),
                service.getUpdatedAt()
        );
    }
}
