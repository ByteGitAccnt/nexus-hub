package com.nexusgate.management;

import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.model.ServiceAuthMode;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "management.jwt.secret=0123456789abcdef0123456789abcdef")
@Testcontainers
class RegisteredServicePersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RegisteredServiceRepository registeredServiceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        registeredServiceRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void persistsRegisteredServiceWithOrganizationAndTimestamps() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Service Owner", "service-owner"));
        Instant beforeSave = Instant.now();

        RegisteredService saved = registeredServiceRepository.saveAndFlush(new RegisteredService(
                organization,
                "Orders",
                "https://orders.example.test/api",
                "/orders",
                "/api",
                ServiceAuthMode.JWT
        ));

        RegisteredService loaded = registeredServiceRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(loaded.getId());
        assertEquals(organization.getId(), loaded.getOrganization().getId());
        assertEquals("Orders", loaded.getName());
        assertEquals("https://orders.example.test/api", loaded.getTargetUrl());
        assertEquals("/orders", loaded.getBasePath());
        assertEquals("/api", loaded.getTargetPath());
        assertTrue(loaded.isActive());
        assertEquals(ServiceAuthMode.JWT, loaded.getAuthMode());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertFalse(loaded.getCreatedAt().isBefore(beforeSave.minusSeconds(1)));
        assertEquals(loaded.getCreatedAt(), loaded.getUpdatedAt());
    }

    @Test
    void enforcesServiceNameUniquenessPerOrganizationOnly() {
        Organization firstOrganization = organizationRepository.saveAndFlush(
                new Organization("First", "first-service-owner"));
        Organization secondOrganization = organizationRepository.saveAndFlush(
                new Organization("Second", "second-service-owner"));
        registeredServiceRepository.saveAndFlush(service(firstOrganization, "Catalog"));

        assertThrows(DataIntegrityViolationException.class,
                () -> registeredServiceRepository.saveAndFlush(service(firstOrganization, "Catalog")));

        RegisteredService sameNameForOtherOrganization = registeredServiceRepository.saveAndFlush(
                service(secondOrganization, "Catalog"));
        assertEquals(secondOrganization.getId(), sameNameForOtherOrganization.getOrganization().getId());
    }

    @Test
    void enforcesOrganizationForeignKeyAndAuthenticationModeCheck() {
        UUID unknownOrganizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO registered_services
                    (id, organization_id, name, target_url, base_path, auth_mode)
                VALUES (?, ?, ?, ?, ?, ?)
                """, serviceId, unknownOrganizationId, "Orphan", "https://example.test", "/orphan", "PUBLIC"));

        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Valid Owner", "valid-service-owner"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO registered_services
                    (id, organization_id, name, target_url, base_path, auth_mode)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organization.getId(), "Bad Mode",
                "https://example.test", "/bad-mode", "BASIC"));
    }

    @Test
    void enforcesReservedNexusHubBasePathConstraint() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Reserved Path Owner", "reserved-path-owner"));

        for (String basePath : new String[]{"/nexushub", "/NexusHub/api"}) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                    INSERT INTO registered_services
                        (id, organization_id, name, target_url, base_path, auth_mode)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), organization.getId(), "Reserved",
                    "https://example.test", basePath, "PUBLIC"));
        }
    }

    @Test
    void allowsNullableTargetPathAndDatabaseActiveDefault() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Default Owner", "default-service-owner"));
        UUID serviceId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO registered_services
                    (id, organization_id, name, target_url, base_path, auth_mode)
                VALUES (?, ?, ?, ?, ?, ?)
                """, serviceId, organization.getId(), "Public API",
                "https://public.example.test", "/public", "PUBLIC");

        RegisteredService loaded = registeredServiceRepository.findById(serviceId).orElseThrow();
        assertTrue(loaded.isActive());
        assertNull(loaded.getTargetPath());
        assertEquals(ServiceAuthMode.PUBLIC, loaded.getAuthMode());
    }

    private static RegisteredService service(Organization organization, String name) {
        return new RegisteredService(
                organization,
                name,
                "https://" + name.toLowerCase() + ".example.test",
                "/" + name.toLowerCase(),
                null,
                ServiceAuthMode.PUBLIC
        );
    }
}
