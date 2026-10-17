package com.nexusgate.management;

import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.CreateApiKeyRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.model.ApiKey;
import com.nexusgate.management.model.ApiKeyPermission;
import com.nexusgate.management.model.ApiKeyStatus;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.RegisteredService;
import com.nexusgate.management.model.ServiceAuthMode;
import com.nexusgate.management.repository.ApiKeyPermissionRepository;
import com.nexusgate.management.repository.ApiKeyRepository;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.RegisteredServiceRepository;
import com.nexusgate.management.repository.UserRepository;
import com.nexusgate.management.service.ApiKeyManagementService;
import com.nexusgate.management.service.OrganizationRegistrationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.jwt.secret=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
@Testcontainers
class ApiKeyManagementIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private RegisteredServiceRepository registeredServiceRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyPermissionRepository permissionRepository;

    @Autowired
    private OrganizationRegistrationService organizationRegistrationService;

    @Autowired
    private ApiKeyManagementService apiKeyManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ApiKeyPermissionRepository permissionRepositorySpy;

    @BeforeEach
    void cleanDatabase() {
        reset(permissionRepositorySpy);
        permissionRepository.deleteAll();
        apiKeyRepository.deleteAll();
        registeredServiceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void adminCreatesKeyForOwnEligibleServicesAndSecretIsReturnedOnlyOnce() throws Exception {
        OrganizationRegistrationResponse owner = register("api-key-owner", "key-owner@example.com");
        RegisteredService service = registerService(owner, "Orders", true, ServiceAuthMode.API_KEY);

        String creationResponse = mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + jwt(owner.admin().id(), owner.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Mobile App Key", List.of(service.getId()), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.apiKey.status").value("ACTIVE"))
                .andExpect(jsonPath("$.apiKey.name").value("Mobile App Key"))
                .andExpect(jsonPath("$.apiKey.permissions[0].serviceId").value(service.getId().toString()))
                .andExpect(jsonPath("$.apiKey.permissions[0].permission").value("INVOKE"))
                .andExpect(jsonPath("$.apiKey.keyHash").doesNotExist())
                .andExpect(jsonPath("$.apiKey.secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String rawSecret = responseString(creationResponse, "secret");
        ApiKey stored = apiKeyRepository.findAll().getFirst();

        assertTrue(rawSecret.startsWith("ng_live_"));
        assertEquals(sha256Hex(rawSecret), stored.getKeyHash());
        assertNotEquals(rawSecret, stored.getKeyHash());
        assertTrue(stored.getLookupPrefix().startsWith("ng_live_"));
        assertTrue(rawSecret.startsWith(stored.getLookupPrefix()));
        assertTrue(stored.getLookupPrefix().length() < rawSecret.length());
        assertEquals(ApiKeyStatus.ACTIVE, stored.getStatus());
        assertEquals(1, permissionRepository.count());
        assertFalse(creationResponse.contains(stored.getKeyHash()));

        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + jwt(owner.admin().id(), owner.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Second Key", List.of(service.getId()), null)))
                .andExpect(status().isCreated());
        assertEquals(2, apiKeyRepository.findAll().stream()
                .map(ApiKey::getLookupPrefix)
                .distinct()
                .count());

        String listResponse = mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + jwt(owner.admin().id(), owner.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].keyHash").doesNotExist())
                .andExpect(jsonPath("$[0].lookupPrefix").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(listResponse.contains(rawSecret));

        String detailResponse = mockMvc.perform(get("/api/v1/api-keys/" + stored.getId())
                        .header("Authorization", "Bearer " + jwt(owner.admin().id(), owner.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.keyHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(detailResponse.contains(rawSecret));
    }

    @Test
    void rejectsInvalidRequestsForeignServicesInactiveServicesAndDuplicateIds() throws Exception {
        OrganizationRegistrationResponse owner = register("api-key-validation", "key-validation@example.com");
        OrganizationRegistrationResponse other = register("api-key-other", "key-other@example.com");
        RegisteredService eligible = registerService(owner, "Eligible", true, ServiceAuthMode.API_KEY);
        RegisteredService foreign = registerService(other, "Foreign", true, ServiceAuthMode.API_KEY);
        RegisteredService inactive = registerService(owner, "Inactive", false, ServiceAuthMode.API_KEY);
        RegisteredService jwtService = registerService(owner, "Jwt Service", true, ServiceAuthMode.JWT);
        String token = jwt(owner.admin().id(), owner.id());
        String[] invalidRequests = {
                createJson("", List.of(eligible.getId()), null),
                createJson("Missing Service", List.of(), null),
                createJson("Past Expiry", List.of(eligible.getId()), Instant.now().minusSeconds(30).toString()),
                createJson("Duplicate Service", List.of(eligible.getId(), eligible.getId()), null),
                createJson("Foreign Service", List.of(foreign.getId()), null),
                createJson("Inactive Service", List.of(inactive.getId()), null),
                createJson("Wrong Auth", List.of(jwtService.getId()), null),
                createJson("Unknown Service", List.of(UUID.randomUUID()), null)
        };

        for (String request : invalidRequests) {
            mockMvc.perform(post("/api/v1/api-keys")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, apiKeyRepository.count());
        assertEquals(0, permissionRepository.count());
    }

    @Test
    void apiKeyEndpointsRequireActiveAdminAndAreOrganizationScoped() throws Exception {
        OrganizationRegistrationResponse owner = register("api-key-auth-owner", "key-auth-owner@example.com");
        OrganizationRegistrationResponse other = register("api-key-auth-other", "key-auth-other@example.com");
        var member = organizationRegistrationService.addUserForAdmin(
                owner.admin().id(),
                new AddUserRequest("Member", "key-member@example.com", TEST_PASSWORD));
        RegisteredService ownerService = registerService(owner, "Owner API", true, ServiceAuthMode.API_KEY);
        RegisteredService otherService = registerService(other, "Other API", true, ServiceAuthMode.API_KEY);
        String created = createWithToken(owner, ownerService, jwt(owner.admin().id(), owner.id()));
        UUID keyId = UUID.fromString(responseString(created, "id"));
        String ownerAdminToken = jwt(owner.admin().id(), owner.id());
        String memberToken = jwt(member.id(), owner.id());

        mockMvc.perform(get("/api/v1/api-keys"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Unauthenticated", List.of(ownerService.getId()), null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Member Key", List.of(ownerService.getId()), null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId + "/status")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/api-keys/" + keyId + "/revoke")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + jwt(other.admin().id(), other.id())))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/api-keys/" + keyId + "/status")
                        .header("Authorization", "Bearer " + jwt(other.admin().id(), other.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/api-keys/" + keyId + "/revoke")
                        .header("Authorization", "Bearer " + jwt(other.admin().id(), other.id())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/api-keys/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerAdminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + ownerAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertEquals(2, registeredServiceRepository.count());
        assertEquals(1, apiKeyRepository.count());
        assertNotEquals(owner.id(), other.id());
    }

    @Test
    void stateTransitionsAreRepeatableExpiryAwareAndRevocationIsPermanent() throws Exception {
        OrganizationRegistrationResponse owner = register("api-key-state", "key-state@example.com");
        RegisteredService service = registerService(owner, "State API", true, ServiceAuthMode.API_KEY);
        String token = jwt(owner.admin().id(), owner.id());
        String created = createWithToken(owner, service, token);
        UUID keyId = UUID.fromString(responseString(created, "id"));
        String base = "/api/v1/api-keys/" + keyId;

        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ApiKey key = apiKeyRepository.findById(keyId).orElseThrow();
        key.setActive(false, Instant.now());
        apiKeyRepository.saveAndFlush(key);
        Instant soonExpiry = Instant.now().plusMillis(500);
        jdbcTemplate.update("UPDATE api_keys SET expires_at = ? WHERE id = ?", Timestamp.from(soonExpiry), keyId);
        Thread.sleep(700);
        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isConflict());
        assertFalse(apiKeyRepository.findById(keyId).orElseThrow().isUsableAt(Instant.now()));

        mockMvc.perform(post(base + "/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
        Instant revokedAt = apiKeyRepository.findById(keyId).orElseThrow().getRevokedAt();
        mockMvc.perform(post(base + "/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertEquals(revokedAt, apiKeyRepository.findById(keyId).orElseThrow().getRevokedAt());
        mockMvc.perform(patch(base + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isConflict());
        assertFalse(apiKeyRepository.findById(keyId).orElseThrow().isUsableAt(Instant.now()));
    }

    @Test
    void preservesLegacySlugPermissionRowsAndEnforcesUuidTenantForeignKeys() throws Exception {
        OrganizationRegistrationResponse owner = register("legacy-permission-org", "legacy@example.com");
        RegisteredService service = registerService(owner, "Legacy API", true, ServiceAuthMode.API_KEY);
        String created = createWithToken(owner, service, jwt(owner.admin().id(), owner.id()));
        UUID keyId = UUID.fromString(responseString(created, "id"));
        String rawLegacySlug = "legacy-service";
        jdbcTemplate.update("""
                INSERT INTO api_key_service_permissions_legacy (id, api_key_id, service_slug, permission)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), keyId, rawLegacySlug, "INVOKE");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_key_service_permissions_legacy WHERE service_slug = ?",
                Integer.class,
                rawLegacySlug));

        UUID invalidPermissionId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO api_key_service_permissions
                    (id, api_key_id, organization_id, service_id, permission)
                VALUES (?, ?, ?, ?, ?)
                """, invalidPermissionId, keyId, UUID.randomUUID(), service.getId(), "INVOKE"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO api_key_service_permissions
                    (id, api_key_id, organization_id, service_id, permission)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), keyId, owner.id(), service.getId(), "ADMIN"));
        assertEquals(1, permissionRepository.count());
    }

    @Test
    void permissionPersistenceFailureRollsBackTheNewKey() {
        OrganizationRegistrationResponse owner = register("api-key-rollback", "rollback-key@example.com");
        RegisteredService service = registerService(owner, "Rollback API", true, ServiceAuthMode.API_KEY);
        doThrow(new IllegalStateException("simulated permission persistence failure"))
                .when(permissionRepositorySpy)
                .saveAllAndFlush(org.mockito.ArgumentMatchers.<Iterable<ApiKeyPermission>>any());

        assertThrows(IllegalStateException.class, () -> apiKeyManagementService.create(
                owner.admin().id(),
                new CreateApiKeyRequest("Rollback", List.of(service.getId()), null)));

        assertEquals(0, apiKeyRepository.count());
        assertEquals(0, permissionRepository.count());
    }

    @Test
    void v4MigrationBackfillsStatusAndPreservesLegacyPermissionData() throws Exception {
        try (PostgreSQLContainer<?> legacyDatabase = new PostgreSQLContainer<>("postgres:17")) {
            legacyDatabase.start();
            Flyway.configure()
                    .dataSource(legacyDatabase.getJdbcUrl(), legacyDatabase.getUsername(), legacyDatabase.getPassword())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3"))
                    .load()
                    .migrate();

            UUID organizationId = UUID.randomUUID();
            UUID activeKeyId = UUID.randomUUID();
            UUID revokedKeyId = UUID.randomUUID();
            try (Connection connection = DriverManager.getConnection(
                    legacyDatabase.getJdbcUrl(),
                    legacyDatabase.getUsername(),
                    legacyDatabase.getPassword())) {
                try (PreparedStatement insertOrganization = connection.prepareStatement("""
                        INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)
                        """)) {
                    insertOrganization.setObject(1, organizationId);
                    insertOrganization.setString(2, "Migration Organization");
                    insertOrganization.setString(3, "migration-organization");
                    insertOrganization.executeUpdate();
                }
                insertLegacyApiKey(connection, activeKeyId, organizationId, "a".repeat(64), "prefix-active", null);
                insertLegacyApiKey(connection, revokedKeyId, organizationId, "b".repeat(64), "prefix-revoked",
                        Instant.now());
                try (PreparedStatement insertPermission = connection.prepareStatement("""
                        INSERT INTO api_key_service_permissions (id, api_key_id, service_slug, permission)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    insertPermission.setObject(1, UUID.randomUUID());
                    insertPermission.setObject(2, activeKeyId);
                    insertPermission.setString(3, "existing-legacy-slug");
                    insertPermission.setString(4, "INVOKE");
                    insertPermission.executeUpdate();
                }
            }

            Flyway.configure()
                    .dataSource(legacyDatabase.getJdbcUrl(), legacyDatabase.getUsername(), legacyDatabase.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    legacyDatabase.getJdbcUrl(),
                    legacyDatabase.getUsername(),
                    legacyDatabase.getPassword())) {
                assertEquals("ACTIVE", selectApiKeyStatus(connection, activeKeyId));
                assertEquals("REVOKED", selectApiKeyStatus(connection, revokedKeyId));
                try (PreparedStatement countPermissions = connection.prepareStatement(
                        "SELECT count(*) FROM api_key_service_permissions_legacy WHERE service_slug = ?")) {
                    countPermissions.setString(1, "existing-legacy-slug");
                    try (ResultSet results = countPermissions.executeQuery()) {
                        assertTrue(results.next());
                        assertEquals(1, results.getInt(1));
                    }
                }
            }
        }
    }

    private String createWithToken(
            OrganizationRegistrationResponse owner,
            RegisteredService service,
            String token
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("Key for " + service.getName(), List.of(service.getId()), null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private OrganizationRegistrationResponse register(String slug, String email) {
        return organizationRegistrationService.register(new OrganizationRegistrationRequest(
                "API Key Test Organization",
                slug,
                "Organization Admin",
                email,
                TEST_PASSWORD));
    }

    private RegisteredService registerService(
            OrganizationRegistrationResponse owner,
            String name,
            boolean active,
            ServiceAuthMode authMode
    ) {
        Organization organization = organizationRepository.findById(owner.id()).orElseThrow();
        RegisteredService service = registeredServiceRepository.saveAndFlush(new RegisteredService(
                organization,
                name,
                "https://service.example.test",
                "/" + name.toLowerCase().replace(' ', '-'),
                null,
                authMode));
        if (!active) {
            service.setActive(false);
            service = registeredServiceRepository.saveAndFlush(service);
        }
        return service;
    }

    private static String createJson(String name, List<UUID> serviceIds, String expiresAt) {
        String ids = serviceIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String expiry = expiresAt == null ? "null" : "\"" + expiresAt + "\"";
        return """
                {
                  "name": "%s",
                  "serviceIds": [%s],
                  "expiresAt": %s
                }
                """.formatted(name, ids, expiry);
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

    private static String responseString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            throw new AssertionError("Response is missing string field " + field);
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new AssertionError("Response field is incomplete: " + field);
        }
        return json.substring(start, end);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void insertLegacyApiKey(
            Connection connection,
            UUID keyId,
            UUID organizationId,
            String keyHash,
            String lookupPrefix,
            Instant revokedAt
    ) throws Exception {
        try (PreparedStatement insertKey = connection.prepareStatement("""
                INSERT INTO api_keys
                    (id, organization_id, name, key_hash, lookup_prefix, created_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL, ?)
                """)) {
            insertKey.setObject(1, keyId);
            insertKey.setObject(2, organizationId);
            insertKey.setString(3, "Legacy key");
            insertKey.setString(4, keyHash);
            insertKey.setString(5, lookupPrefix);
            insertKey.setObject(6, revokedAt == null ? null : Timestamp.from(revokedAt));
            insertKey.executeUpdate();
        }
    }

    private static String selectApiKeyStatus(Connection connection, UUID keyId) throws Exception {
        try (PreparedStatement selectStatus = connection.prepareStatement(
                "SELECT status FROM api_keys WHERE id = ?")) {
            selectStatus.setObject(1, keyId);
            try (ResultSet results = selectStatus.executeQuery()) {
                assertTrue(results.next());
                return results.getString(1);
            }
        }
    }
}
