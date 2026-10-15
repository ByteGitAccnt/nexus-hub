package com.nexusgate.management.service;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.dto.UpdateUserStatusRequest;
import com.nexusgate.management.dto.UserResponse;
import com.nexusgate.management.exception.ResourceConflictException;
import com.nexusgate.management.exception.ResourceNotFoundException;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class OrganizationRegistrationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public OrganizationRegistrationService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public OrganizationRegistrationResponse register(OrganizationRegistrationRequest request) {
        String slug = normalize(request.organizationSlug());
        String email = normalize(request.adminEmail());

        if (organizationRepository.existsBySlug(slug)) {
            throw new ResourceConflictException("Organization slug is already registered");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResourceConflictException("Email is already registered");
        }

        try {
            Organization organization = organizationRepository.saveAndFlush(
                    new Organization(request.organizationName().trim(), slug)
            );
            User admin = userRepository.saveAndFlush(new User(
                    organization,
                    request.adminName().trim(),
                    email,
                    passwordEncoder.encode(request.adminPassword()),
                    UserRole.ADMIN
            ));
            return toResponse(organization, admin);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("Organization slug or email is already registered");
        }
    }

    @Transactional
    public UserResponse addUserForAdmin(UUID actorUserId, AddUserRequest request) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new AccessDeniedException("Active organization administrator required"));
        Organization organization = actor.getOrganization();
        if (actor.getRole() != UserRole.ADMIN
                || actor.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new AccessDeniedException("Active organization administrator required");
        }

        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResourceConflictException("Email is already registered");
        }
        try {
            User created = userRepository.saveAndFlush(new User(
                    organization,
                    request.name().trim(),
                    email,
                    passwordEncoder.encode(request.password()),
                    UserRole.MEMBER
            ));
            return new UserResponse(
                    created.getId(),
                    organization.getId(),
                    created.getName(),
                    created.getEmail(),
                    created.getRole(),
                    created.getStatus(),
                    created.getCreatedAt()
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("Email is already registered");
        }
    }

    @Transactional
    public UserResponse setMemberStatusForAdmin(
            UUID actorUserId,
            UUID targetUserId,
            UpdateUserStatusRequest request
    ) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new AccessDeniedException("Active organization administrator required"));
        Organization organization = actor.getOrganization();
        if (actor.getRole() != UserRole.ADMIN
                || actor.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new AccessDeniedException("Active organization administrator required");
        }

        User target = userRepository.findById(targetUserId)
                .filter(user -> user.getOrganization().getId().equals(organization.getId()))
                .filter(user -> user.getRole() == UserRole.MEMBER)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));

        target.setStatus(request.active() ? UserStatus.ACTIVE : UserStatus.DISABLED);
        User updated = userRepository.saveAndFlush(target);
        return new UserResponse(
                updated.getId(),
                organization.getId(),
                updated.getName(),
                updated.getEmail(),
                updated.getRole(),
                updated.getStatus(),
                updated.getCreatedAt()
        );
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static OrganizationRegistrationResponse toResponse(Organization organization, User admin) {
        return new OrganizationRegistrationResponse(
                organization.getId(),
                organization.getName(),
                organization.getSlug(),
                organization.getStatus(),
                organization.getCreatedAt(),
                new OrganizationRegistrationResponse.AdminResponse(
                        admin.getId(),
                        admin.getName(),
                        admin.getEmail(),
                        admin.getRole(),
                        admin.getStatus(),
                        admin.getCreatedAt()
                )
        );
    }
}
