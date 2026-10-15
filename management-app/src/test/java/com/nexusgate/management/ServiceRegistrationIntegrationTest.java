package com.nexusgate.management;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import com.nexusgate.management.repository.UserRepository;
import com.nexusgate.management.service.OrganizationRegistrationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.jwt.secret=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
@Testcontainers
class ServiceRegistrationIntegrationTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String TEST_PASSWORD = "A-strong-password-123";
    private static final String ISSUER = "http://localhost:8090";
    private static final String AUDIENCE = "nexushub";

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
    private RegisteredServiceRepository registeredServiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRegistrationService organizationRegistrationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        registeredServiceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void adminRegistersServiceInPersistedOrganizationAndGetsSafeResponse() throws Exception {
        OrganizationRegistrationResponse owner = register("service-owner", "owner@example.com");
        String body = """
                {
                  "name": " Orders ",
                  "targetUrl": "https://orders.example.test/v1",
                  "basePath": "/orders",
                  "targetPath": "/v1",
                  "authMode": "JWT",
                  "organizationId": "%s"
                }
                """.formatted(UUID.randomUUID());

        String response = mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwt(owner.admin().id(), owner.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(owner.id().toString()))
                .andExpect(jsonPath("$.name").value("Orders"))
                .andExpect(jsonPath("$.targetUrl").value("https://orders.example.test/v1"))
                .andExpect(jsonPath("$.basePath").value("/orders"))
                .andExpect(jsonPath("$.targetPath").value("/v1"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.authMode").value("JWT"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.keyHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, registeredServiceRepository.count());
        RegisteredService persisted = registeredServiceRepository.findAll().getFirst();
        assertEquals(owner.id(), persisted.getOrganization().getId());
        assertTrue(response.contains(persisted.getId().toString()));
        assertTrue(persisted.isActive());
    }

    @Test
    void sameNameConflictsOnlyWithinTheCallerOrganization() throws Exception {
        OrganizationRegistrationResponse first = register("first-service-org", "first@example.com");
        OrganizationRegistrationResponse second = register("second-service-org", "second@example.com");
        String payload = serviceJson("Catalog", "https://catalog.example.test", "/catalog", null, "PUBLIC");

        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwt(first.admin().id(), first.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwt(first.admin().id(), first.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwt(second.admin().id(), second.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        assertEquals(2, registeredServiceRepository.count());
    }

    @Test
    void rejectsInvalidFieldsAndUnsafeUrlSyntaxWithoutPersisting() throws Exception {
        OrganizationRegistrationResponse owner = register("validation-service-org", "validation@example.com");
        String accessToken = jwt(owner.admin().id(), owner.id());
        String[] invalidPayloads = {
                serviceJson("", "https://valid.example.test", "/valid", null, "PUBLIC"),
                serviceJson("Bad URL", "ftp://bad.example.test", "/bad-url", null, "PUBLIC"),
                serviceJson("Credentials", "https://user:pass@example.test", "/credentials", null, "PUBLIC"),
                serviceJson("Fragment", "https://example.test/path#fragment", "/fragment", null, "PUBLIC"),
                serviceJson("Path", "https://example.test", "relative", null, "PUBLIC"),
                serviceJson("Target Path", "https://example.test", "/target-path", "//other/path", "PUBLIC"),
                serviceJson("Bad Mode", "https://example.test", "/bad-mode", null, "BASIC")
        };

        for (String payload : invalidPayloads) {
            mockMvc.perform(post("/api/v1/services")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, registeredServiceRepository.count());
    }

    @Test
    void requiresAdminAuthenticationAndRejectsMissingIdentity() throws Exception {
        OrganizationRegistrationResponse owner = register("secured-service-org", "secured@example.com");
        var member = organizationRegistrationService.addUserForAdmin(
                owner.admin().id(),
                new AddUserRequest("Member", "member@example.com", TEST_PASSWORD));
        String payload = serviceJson("Protected", "https://protected.example.test", "/protected", null, "PUBLIC");

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + jwt(member.id(), owner.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
        assertEquals(0, registeredServiceRepository.count());
    }

    private OrganizationRegistrationResponse register(String slug, String email) {
        return organizationRegistrationService.register(new OrganizationRegistrationRequest(
                "Service Test Organization",
                slug,
                "Organization Admin",
                email,
                TEST_PASSWORD));
    }

    private static String serviceJson(
            String name,
            String targetUrl,
            String basePath,
            String targetPath,
            String authMode
    ) {
        String targetPathJson = targetPath == null ? "null" : "\"" + targetPath + "\"";
        return """
                {
                  "name": "%s",
                  "targetUrl": "%s",
                  "basePath": "%s",
                  "targetPath": %s,
                  "authMode": "%s"
                }
                """.formatted(name, targetUrl, basePath, targetPathJson, authMode);
    }

    private static String jwt(UUID userId, UUID organizationId) {
        Instant issuedAt = Instant.now().minusSeconds(1);
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .claim("userid", userId.toString())
                .claim("organizationid", organizationId.toString())
                .claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
