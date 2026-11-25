package com.nexusgate.management;


import com.nexusgate.management.dto.*;
import com.nexusgate.management.exception.InvalidApiKeyException;
import com.nexusgate.management.model.*;
import com.nexusgate.management.repository.ApiKeyPermissionRepository;
import com.nexusgate.management.repository.ApiKeyRepository;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import com.nexusgate.management.service.ApiKeyManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;



@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceTest {
    private static final String SECRET_PREFIX = "ng_live_";
    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private ApiKeyPermissionRepository permissionRepository;

    @Mock
    private RegisteredServiceRepository registeredServiceRepository;

    @InjectMocks
    private ApiKeyManagementService apiKeyManagementService;


    @Test
    void internalValidationValidateApiKeyService() throws Exception {
        String apiKey = SECRET_PREFIX + "A".repeat(43);
        Method hashSecretMethod = ApiKeyManagementService.class
                .getDeclaredMethod("hashSecret", String.class);
        hashSecretMethod.setAccessible(true);
        String hashedSecret = (String) hashSecretMethod.invoke(apiKeyManagementService, apiKey);
        UUID organizationId = UUID.fromString("123e4567-e89b-12d3-a456-426614174111");
        UUID apiKeyId = UUID.fromString("123e4567-e89b-12d3-a456-426614174222");
        UUID serviceId = UUID.fromString("123e4567-e89b-12d3-a456-426614174333");

        // Mock the API key and its organization.
        ApiKey key = mock(ApiKey.class);
        Organization organization = mock(Organization.class);

        given(key.getId()).willReturn(apiKeyId);
        given(key.getOrganization()).willReturn(organization);
        given(organization.getId()).willReturn(organizationId);
        given(organization.getStatus()).willReturn(OrganizationStatus.ACTIVE);
        given(key.getStatus()).willReturn(ApiKeyStatus.ACTIVE);
        given(key.getRevokedAt()).willReturn(null);
        given(key.getExpiresAt()).willReturn(null);
        given(key.getKeyHash()).willReturn(hashedSecret);


        String lookupPrefix = apiKey.substring(0, SECRET_PREFIX.length() + 12);

        given(apiKeyRepository.findByLookupPrefix(lookupPrefix))
                .willReturn(Optional.of(key));

        // Return a service permitted by the API key.
        given(permissionRepository
                .findServiceIdsByApiKeyIdAndPermission(
                        apiKeyId,
                        ApiKeyPermissionType.INVOKE
                ))
                .willReturn(List.of(serviceId));

        RegisteredService service = mock(RegisteredService.class);

        given(service.getId()).willReturn(serviceId);
        given(service.getName()).willReturn("User Service");
        given(service.getTargetUrl())
                .willReturn("https://api.example.com");
        given(service.getBasePath()).willReturn("/api/v1");
        given(service.getTargetPath()).willReturn("/users");

        given(registeredServiceRepository.findEligibleForApiKey(
                List.of(serviceId),
                organizationId,
                ServiceAuthMode.API_KEY
        )).willReturn(List.of(service));

        // Act
        ValidationResult result = apiKeyManagementService.validate(apiKey, "/api/v1");

        // Assert
        assertTrue(result.valid());
        assertEquals(apiKeyId, result.apiKeyId());
        assertEquals(organizationId, result.organizationId());


        ValidServiceDetails actual = result.service();

        assertEquals(serviceId,actual.serviceId());
        assertEquals("User Service", actual.serviceName());
        assertEquals("https://api.example.com", actual.serviceTargetUrl());
        assertEquals("/api/v1", actual.serviceBasePath());
        assertEquals("/users", actual.serviceTargetPath());

        then(apiKeyRepository).should()
                .findByLookupPrefix(lookupPrefix);

        then(permissionRepository).should()
                .findServiceIdsByApiKeyIdAndPermission(
                        apiKeyId,
                        ApiKeyPermissionType.INVOKE
                );

        then(registeredServiceRepository).should()
                .findEligibleForApiKey(
                        List.of(serviceId),
                        organizationId,
                        ServiceAuthMode.API_KEY
                );
    }

    @Test
    void validate_shouldRejectUnknownApiKey() throws Exception {
        String apiKey = SECRET_PREFIX + "A".repeat(43);
        Method hashSecretMethod = ApiKeyManagementService.class
                .getDeclaredMethod("hashSecret", String.class);
        hashSecretMethod.setAccessible(true);
        String hashedSecret = (String) hashSecretMethod.invoke(apiKeyManagementService, apiKey);


        String lookupPrefix = apiKey.substring(0, SECRET_PREFIX.length() + 12);

        given(apiKeyRepository.findByLookupPrefix(lookupPrefix))
                .willReturn(Optional.empty());

        assertThrows(
                InvalidApiKeyException.class,
                () -> apiKeyManagementService.validate(apiKey, "/api/v1")
        );

        then(permissionRepository).shouldHaveNoInteractions();
        then(registeredServiceRepository).shouldHaveNoInteractions();
    }
}