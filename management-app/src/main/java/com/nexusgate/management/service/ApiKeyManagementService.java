package com.nexusgate.management.service;

import com.nexusgate.management.dto.ApiKeyCreatedResponse;
import com.nexusgate.management.dto.ApiKeyResponse;
import com.nexusgate.management.dto.ApiKeyServicePermissionResponse;
import com.nexusgate.management.dto.CreateApiKeyRequest;
import com.nexusgate.management.exception.ResourceConflictException;
import com.nexusgate.management.exception.ResourceNotFoundException;
import com.nexusgate.management.exception.InvalidRequestException;
import com.nexusgate.management.model.ApiKey;
import com.nexusgate.management.model.ApiKeyPermission;
import com.nexusgate.management.model.ApiKeyPermissionType;
import com.nexusgate.management.model.ApiKeyStatus;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.model.ServiceAuthMode;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.repository.ApiKeyPermissionRepository;
import com.nexusgate.management.repository.ApiKeyRepository;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiKeyManagementService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String SECRET_PREFIX = "ng_live_";
    private static final int LOOKUP_RANDOM_LENGTH = 12;
    private static final int MAX_PREFIX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyPermissionRepository permissionRepository;
    private final RegisteredServiceRepository serviceRepository;

    public ApiKeyManagementService(
            UserRepository userRepository,
            ApiKeyRepository apiKeyRepository,
            ApiKeyPermissionRepository permissionRepository,
            RegisteredServiceRepository serviceRepository) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.permissionRepository = permissionRepository;
        this.serviceRepository = serviceRepository;
    }

    @Transactional
    public ApiKeyCreatedResponse create(UUID actorUserId, CreateApiKeyRequest request) {
        Organization organization = requireActiveAdminOrganization(actorUserId);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new InvalidRequestException("Expiry must be in the future");
        }

        List<UUID> requestedIds = request.serviceIds();
        if (new HashSet<>(requestedIds).size() != requestedIds.size()) {
            throw new InvalidRequestException("Service IDs must be unique");
        }
        List<RegisteredService> services = serviceRepository.findEligibleForApiKey(
                requestedIds,
                organization.getId(),
                ServiceAuthMode.API_KEY);
        if (services.size() != requestedIds.size()) {
            throw new InvalidRequestException(
                    "Every service must be active, API_KEY-enabled, and belong to the organization");
        }

        GeneratedSecret generatedSecret = generateSecret();
        ApiKey apiKey = new ApiKey(
                organization,
                request.name().trim(),
                hashSecret(generatedSecret.secret()),
                generatedSecret.lookupPrefix(),
                request.expiresAt());
        List<ApiKeyPermission> permissions = new ArrayList<>(services.size());
        for (RegisteredService service : services) {
            ApiKeyPermission permission = new ApiKeyPermission(service, ApiKeyPermissionType.INVOKE);
            apiKey.addPermission(permission);
            permissions.add(permission);
        }

        apiKeyRepository.saveAndFlush(apiKey);
        permissionRepository.saveAllAndFlush(permissions);
        return new ApiKeyCreatedResponse(generatedSecret.secret(), toResponse(apiKey, permissions));
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID actorUserId) {
        Organization organization = requireActiveAdminOrganization(actorUserId);
        List<ApiKey> keys = apiKeyRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organization.getId());
        List<ApiKeyPermission> permissions = permissionsFor(keys);
        return keys.stream()
                .map(key -> toResponse(key, permissionsForKey(permissions, key.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApiKeyResponse get(UUID actorUserId, UUID keyId) {
        Organization organization = requireActiveAdminOrganization(actorUserId);
        ApiKey key = findKey(organization.getId(), keyId);
        return toResponse(key, permissionRepository.findAllByApiKeyIdIn(List.of(keyId)));
    }

    @Transactional
    public ApiKeyResponse setActive(UUID actorUserId, UUID keyId, boolean active) {
        Organization organization = requireActiveAdminOrganization(actorUserId);
        ApiKey key = findKeyForUpdate(organization.getId(), keyId);
        try {
            if (active) {
                key.setActive(true, Instant.now());
            } else {
                key.setInactive();
            }
        } catch (IllegalStateException exception) {
            throw new ResourceConflictException(exception.getMessage());
        }
        apiKeyRepository.saveAndFlush(key);
        return toResponse(key, permissionRepository.findAllByApiKeyIdIn(List.of(keyId)));
    }

    @Transactional
    public ApiKeyResponse revoke(UUID actorUserId, UUID keyId) {
        Organization organization = requireActiveAdminOrganization(actorUserId);
        ApiKey key = findKeyForUpdate(organization.getId(), keyId);
        key.revoke();
        apiKeyRepository.saveAndFlush(key);
        return toResponse(key, permissionRepository.findAllByApiKeyIdIn(List.of(keyId)));
    }

    private Organization requireActiveAdminOrganization(UUID actorUserId) {
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new AccessDeniedException("Active organization administrator required"));
        Organization organization = actor.getOrganization();
        if (actor.getRole() != UserRole.ADMIN
                || actor.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new AccessDeniedException("Active organization administrator required");
        }
        return organization;
    }

    private ApiKey findKey(UUID organizationId, UUID keyId) {
        return apiKeyRepository.findByIdAndOrganizationId(keyId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
    }

    private ApiKey findKeyForUpdate(UUID organizationId, UUID keyId) {
        return apiKeyRepository.findByIdAndOrganizationIdForUpdate(keyId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
    }

    private List<ApiKeyPermission> permissionsFor(List<ApiKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return permissionRepository.findAllByApiKeyIdIn(
                keys.stream().map(ApiKey::getId).toList());
    }

    private static List<ApiKeyPermission> permissionsForKey(List<ApiKeyPermission> permissions, UUID keyId) {
        return permissions.stream()
                .filter(permission -> permission.getApiKey().getId().equals(keyId))
                .toList();
    }

    private static ApiKeyResponse toResponse(ApiKey key, List<ApiKeyPermission> permissions) {
        List<ApiKeyServicePermissionResponse> permissionResponses = permissions.stream()
                .map(permission -> new ApiKeyServicePermissionResponse(
                        permission.getService().getId(),
                        permission.getService().getName(),
                        permission.getPermission()))
                .toList();
        return new ApiKeyResponse(
                key.getId(),
                key.getOrganization().getId(),
                key.getName(),
                key.getLookupPrefix(),
                key.getStatus(),
                key.getCreatedAt(),
                key.getExpiresAt(),
                key.getRevokedAt(),
                permissionResponses);
    }

    private GeneratedSecret generateSecret() {
        for (int attempt = 0; attempt < MAX_PREFIX_ATTEMPTS; attempt++) {
            byte[] randomBytes = new byte[32];
            SECURE_RANDOM.nextBytes(randomBytes);
            String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            String secret = SECRET_PREFIX + randomPart;
            String lookupPrefix = SECRET_PREFIX + randomPart.substring(0, LOOKUP_RANDOM_LENGTH);
            if (!apiKeyRepository.existsByLookupPrefix(lookupPrefix)) {
                return new GeneratedSecret(secret, lookupPrefix);
            }
        }
        throw new IllegalStateException("Unable to generate a unique API-key lookup prefix");
    }

    private static String hashSecret(String secret) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record GeneratedSecret(String secret, String lookupPrefix) {
    }
}
