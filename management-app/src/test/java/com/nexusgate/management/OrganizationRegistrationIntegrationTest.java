package com.nexusgate.management;

import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.UserResponse;
import com.nexusgate.management.exception.ResourceConflictException;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.UserRepository;
import com.nexusgate.management.service.OrganizationRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.validation.Validator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.jwt.secret=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
@Testcontainers
class OrganizationRegistrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationRegistrationService registrationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Validator validator;

    @MockitoSpyBean
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        reset(userRepository);
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void registersOrganizationAndInitialAdminWithSafeResponse() throws Exception {
        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("Acme Systems", "Acme-Team", "Ada Admin", "ADA@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Acme Systems"))
                .andExpect(jsonPath("$.slug").value("acme-team"))
                .andExpect(jsonPath("$.admin.name").value("Ada Admin"))
                .andExpect(jsonPath("$.admin.email").value("ada@example.com"))
                .andExpect(jsonPath("$.admin.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.admin.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        User admin = userRepository.findByEmail("ada@example.com").orElseThrow();
        assertEquals(UserRole.ADMIN, admin.getRole());
        Organization persistedOrganization = organizationRepository.findById(
                admin.getOrganization().getId()
        ).orElseThrow();
        assertEquals("acme-team", persistedOrganization.getSlug());
        assertNotEquals("A-strong-password-123", admin.getPasswordHash());
        assertTrue(admin.getPasswordHash().startsWith("$2"));
    }

    @Test
    void rejectsDuplicateSlugAndDuplicateEmail() throws Exception {
        register("first-org", "first@example.com");

        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("Second", "FIRST-ORG", "Second Admin", "second@example.com")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("Second", "second-org", "Second Admin", "first@example.com")))
                .andExpect(status().isConflict());

        assertEquals(1, organizationRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        String invalid = """
                {
                  "organizationName": "",
                  "organizationSlug": "not a slug",
                  "adminName": "",
                  "adminEmail": "bad-email",
                  "adminPassword": "short"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));

        assertEquals(0, organizationRepository.count());
    }

    @Test
    void ignoresClientRoleAndOrganizationIdInsteadOfGrantingPrivilegesOrTenantChoice() throws Exception {
        UUID arbitraryOrganizationId = UUID.randomUUID();
        String payload = """
                {
                  "organizationName": "Trusted Org",
                  "organizationSlug": "trusted-org",
                  "adminName": "Initial Admin",
                  "adminEmail": "initial@example.com",
                  "adminPassword": "A-strong-password-123",
                  "role": "MEMBER",
                  "organizationId": "%s"
                }
                """.formatted(arbitraryOrganizationId);

        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admin.role").value("ADMIN"));

        User admin = userRepository.findByEmail("initial@example.com").orElseThrow();
        assertNotEquals(arbitraryOrganizationId, admin.getOrganization().getId());
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @Test
    void rollsBackOrganizationWhenAdminPersistenceFails() {
        doThrow(new IllegalStateException("simulated persistence failure"))
                .when(userRepository)
                .saveAndFlush(any(User.class));

        OrganizationRegistrationRequest request = new OrganizationRegistrationRequest(
                "Rollback Organization",
                "rollback-org",
                "Rollback Admin",
                "rollback@example.com",
                "A-strong-password-123"
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> registrationService.register(request)
        );

        assertEquals("simulated persistence failure", exception.getMessage());
        assertFalse(organizationRepository.existsBySlug("rollback-org"));
    }

    @Test
    void adminCreationServiceAssignsMemberAndHashesPasswordWithinAdminOrganization() {
        OrganizationRegistrationResponse organization = registerOrganization("owner-org", "owner@example.com");

        UserResponse created = registrationService.addUserForAdmin(
                organization.admin().id(),
                new AddUserRequest("New Member", "MEMBER@example.com", "A-strong-password-123")
        );

        assertEquals(UserRole.MEMBER, created.role());
        assertEquals(organization.id(), created.organizationId());
        assertEquals("member@example.com", created.email());
        assertFalse(created.toString().contains("password"));
        User persisted = userRepository.findByEmail("member@example.com").orElseThrow();
        assertNotEquals("A-strong-password-123", persisted.getPasswordHash());
        assertTrue(passwordEncoder.matches("A-strong-password-123", persisted.getPasswordHash()));
    }

    @Test
    void adminCreationServiceRejectsDuplicateEmailAcrossOrganizations() {
        OrganizationRegistrationResponse first = registerOrganization("first-org", "first-admin@example.com");
        registerOrganization("second-org", "second-admin@example.com");

        assertThrows(ResourceConflictException.class, () -> registrationService.addUserForAdmin(
                first.admin().id(),
                new AddUserRequest("Duplicate", "SECOND-ADMIN@example.com", "A-strong-password-123")
        ));
        assertEquals(2, userRepository.count());
    }

    @Test
    void addUserRequestValidationRejectsMissingOrMalformedFields() {
        AddUserRequest invalid = new AddUserRequest("", "not-an-email", "short");

        assertFalse(validator.validate(invalid).isEmpty());
    }

    @Test
    void adminCreationServiceRejectsNonAdminCaller() {
        OrganizationRegistrationResponse organization = registerOrganization("non-admin-org", "admin@example.com");
        Organization persistedOrganization = organizationRepository.findById(organization.id()).orElseThrow();
        User member = userRepository.saveAndFlush(new User(
                persistedOrganization,
                "Member User",
                "member@example.com",
                passwordEncoder.encode("A-strong-password-123"),
                UserRole.MEMBER
        ));

        assertThrows(AccessDeniedException.class, () -> registrationService.addUserForAdmin(
                member.getId(),
                new AddUserRequest("Another Member", "another@example.com", "A-strong-password-123")
        ));
        assertFalse(userRepository.existsByEmail("another@example.com"));
    }

    @Test
    void creationIsScopedToAuthenticatedAdminsPersistedOrganization() {
        OrganizationRegistrationResponse callerOrganization = registerOrganization(
                "caller-org",
                "caller-admin@example.com"
        );
        OrganizationRegistrationResponse otherOrganization = registerOrganization(
                "other-org",
                "other-admin@example.com"
        );
        AddUserRequest request = new AddUserRequest(
                "Scoped Member",
                "scoped@example.com",
                "A-strong-password-123"
        );

        UserResponse created = registrationService.addUserForAdmin(callerOrganization.admin().id(), request);

        assertEquals(callerOrganization.id(), created.organizationId());
        assertEquals(UserRole.MEMBER, created.role());
        User persisted = userRepository.findByEmail("scoped@example.com").orElseThrow();
        assertEquals(callerOrganization.id(), persisted.getOrganization().getId());
        assertNotEquals(otherOrganization.id(), created.organizationId());
        assertFalse(userRepository.findByEmail("other-admin@example.com").isEmpty());
    }

    private OrganizationRegistrationResponse registerOrganization(String slug, String email) {
        return registrationService.register(new OrganizationRegistrationRequest(
                "Test Organization",
                slug,
                "Organization Admin",
                email,
                "A-strong-password-123"
        ));
    }

    private void register(String slug, String email) throws Exception {
        mockMvc.perform(post("/api/v1/organizations/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("Organization", slug, "Admin", email)))
                .andExpect(status().isCreated());
    }

    private String registrationJson(String name, String slug, String adminName, String email) throws Exception {
        return """
                {
                  "organizationName": "%s",
                  "organizationSlug": "%s",
                  "adminName": "%s",
                  "adminEmail": "%s",
                  "adminPassword": "A-strong-password-123"
                }
                """.formatted(name, slug, adminName, email);
    }
}
