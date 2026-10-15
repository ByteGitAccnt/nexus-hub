package com.nexusgate.management.service;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.exception.ResourceConflictException;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

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
    public User addUser(Organization organization, AddUserRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResourceConflictException("Email is already registered");
        }
        try {
            return userRepository.saveAndFlush(new User(
                    organization,
                    request.name().trim(),
                    email,
                    passwordEncoder.encode(request.password()),
                    UserRole.MEMBER
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("Email is already registered");
        }
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
