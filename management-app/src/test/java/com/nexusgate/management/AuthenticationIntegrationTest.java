package com.nexusgate.management;

import com.nexusgate.management.dto.LoginRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.model.RefreshToken;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserRole;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.RefreshTokenRepository;
import com.nexusgate.management.repository.UserRepository;
import com.nexusgate.management.service.AuthenticationService;
import com.nexusgate.management.service.OrganizationRegistrationService;
import com.nexusgate.management.dto.AddUserRequest;
import com.nexusgate.management.dto.UserResponse;
import io.jsonwebtoken.Claims;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.jwt.secret=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationIntegrationTest {

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
        private OrganizationRegistrationService registrationService;

        @Autowired
        private AuthenticationService authenticationService;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @MockitoSpyBean
        private RefreshTokenRepository refreshTokenRepository;

        @BeforeEach
        void cleanDatabase() {
                reset(refreshTokenRepository);
                refreshTokenRepository.deleteAll();
                userRepository.deleteAll();
                organizationRepository.deleteAll();
        }

        @Test
        void logsInWithSignedGatewayCompatibleJwtAndHashedRefreshToken() throws Exception {
                OrganizationRegistrationResponse registered = register("login-org", "admin@example.com");
                Instant beforeLogin = Instant.now();
                String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("ADMIN@example.com", TEST_PASSWORD)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                                .andExpect(jsonPath("$.expiresIn").value(900))
                                .andExpect(jsonPath("$.user.id").value(registered.admin().id().toString()))
                                .andExpect(jsonPath("$.user.organizationId").value(registered.id().toString()))
                                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                                .andExpect(jsonPath("$.password").doesNotExist())
                                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                String accessToken = responseString(responseBody, "accessToken");
                Claims claims = Jwts.parser()
                                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                                .requireIssuer(ISSUER)
                                .requireAudience(AUDIENCE)
                                .build()
                                .parseSignedClaims(accessToken)
                                .getPayload();

                assertEquals(registered.admin().id().toString(), claims.get("userid", String.class));
                assertEquals(registered.id().toString(), claims.get("organizationid", String.class));
                assertEquals("ADMIN", claims.get("role", String.class));
                assertFalse(claims.getIssuedAt().toInstant().isBefore(beforeLogin.minusSeconds(1)));
                assertTrue(claims.getExpiration().toInstant().isAfter(Instant.now()));
                assertTrue(claims.getExpiration().toInstant()
                                .isBefore(claims.getIssuedAt().toInstant().plusSeconds(901)));

                String rawRefreshToken = responseString(responseBody, "refreshToken");
                assertEquals(43, rawRefreshToken.length());
                assertFalse(responseBody.contains(TEST_PASSWORD));
                assertFalse(responseBody.contains(userRepository.findByEmail("admin@example.com")
                                .orElseThrow().getPasswordHash()));

                RefreshToken storedToken = refreshTokenRepository.findAll().getFirst();
                assertEquals(sha256Hex(rawRefreshToken), storedToken.getTokenHash());
                assertNotEquals(rawRefreshToken, storedToken.getTokenHash());
                assertTrue(storedToken.getExpiresAt().isAfter(Instant.now().plusSeconds(29L * 24 * 60 * 60)));
                assertTrue(storedToken.getExpiresAt().isBefore(Instant.now().plusSeconds(31L * 24 * 60 * 60)));
                assertEquals(registered.admin().id(), storedToken.getUser().getId());
                assertEquals(registered.id(), storedToken.getOrganization().getId());
                assertFalse(responseBody.contains(storedToken.getTokenHash()));
        }

        @Test
        void unknownEmailAndWrongPasswordReturnTheSameUnauthorizedResponse() throws Exception {
                register("invalid-login-org", "known@example.com");

                String unknownEmailResponse = mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("missing@example.com", TEST_PASSWORD)))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.detail").value("Invalid email or password"))
                                .andReturn().getResponse().getContentAsString();

                String wrongPasswordResponse = mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("known@example.com", "Wrong-password-123")))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.detail").value("Invalid email or password"))
                                .andReturn().getResponse().getContentAsString();

                assertEquals("Invalid email or password", responseString(unknownEmailResponse, "detail"));
                assertEquals(responseString(unknownEmailResponse, "detail"),
                                responseString(wrongPasswordResponse, "detail"));
                assertEquals(0, refreshTokenRepository.count());
        }

        @Test
        void loginIgnoresClientTenantAndRoleAndUsesPersistedIdentity() throws Exception {
                OrganizationRegistrationResponse registered = register("trusted-login-org", "trusted@example.com");
                UUID forgedOrganizationId = UUID.randomUUID();
                String payload = """
                                {
                                  "email": "trusted@example.com",
                                  "password": "%s",
                                  "organizationId": "%s",
                                  "role": "MEMBER"
                                }
                                """.formatted(TEST_PASSWORD, forgedOrganizationId);

                String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.user.organizationId").value(registered.id().toString()))
                                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                                .andReturn().getResponse().getContentAsString();

                Claims claims = Jwts.parser()
                                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                                .requireIssuer(ISSUER)
                                .requireAudience(AUDIENCE)
                                .build()
                                .parseSignedClaims(responseString(responseBody, "accessToken"))
                                .getPayload();
                assertEquals(registered.id().toString(), claims.get("organizationid", String.class));
                assertNotEquals(forgedOrganizationId.toString(), claims.get("organizationid", String.class));
                assertEquals("ADMIN", claims.get("role", String.class));
        }

        @Test
        void refreshTokenPersistenceFailureDoesNotCompleteLogin() {
                OrganizationRegistrationResponse registered = register("rollback-login-org", "rollback@example.com");
                doThrow(new IllegalStateException("simulated refresh persistence failure"))
                                .when(refreshTokenRepository)
                                .saveAndFlush(any(RefreshToken.class));

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                () -> authenticationService
                                                .login(new LoginRequest("rollback@example.com", TEST_PASSWORD)));

                assertEquals("simulated refresh persistence failure", exception.getMessage());
                assertEquals(registered.id(), organizationRepository.findById(registered.id()).orElseThrow().getId());
                assertEquals(0, refreshTokenRepository.count());
        }

        @Test
        void adminWithValidJwtCreatesMemberInOwnOrganization() throws Exception {
                OrganizationRegistrationResponse registered = register("create-user-org", "owner@example.com");
                String forgedFields = """
                                {
                                  "name": "New Member",
                                  "email": "new-member@example.com",
                                  "password": "A-strong-password-123",
                                  "organizationId": "%s",
                                  "role": "ADMIN"
                                }
                                """.formatted(UUID.randomUUID());

                String body = mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + jwt(
                                                registered.admin().id(),
                                                registered.id(),
                                                Instant.now().plusSeconds(300),
                                                TEST_SECRET))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(forgedFields))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.organizationId").value(registered.id().toString()))
                                .andExpect(jsonPath("$.role").value("MEMBER"))
                                .andExpect(jsonPath("$.password").doesNotExist())
                                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                                .andReturn().getResponse().getContentAsString();

                assertFalse(body.contains("A-strong-password-123"));
                User created = userRepository.findByEmail("new-member@example.com").orElseThrow();
                assertEquals(registered.id(), created.getOrganization().getId());
                assertEquals(UserRole.MEMBER, created.getRole());
                assertTrue(passwordEncoder.matches("A-strong-password-123", created.getPasswordHash()));
        }

        @Test
        void protectedUserCreationRejectsMissingAuthentication() throws Exception {
                mockMvc.perform(post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("missing-auth@example.com")))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void protectedUserCreationRejectsInvalidSignatureAndExpiredJwt() throws Exception {
                OrganizationRegistrationResponse registered = register("invalid-jwt-org", "jwt-admin@example.com");
                String invalidSignature = jwt(
                                registered.admin().id(),
                                registered.id(),
                                Instant.now().plusSeconds(300),
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                String expired = jwt(
                                registered.admin().id(),
                                registered.id(),
                                Instant.now().minusSeconds(30),
                                TEST_SECRET);

                mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + invalidSignature)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("bad-signature@example.com")))
                                .andExpect(status().isUnauthorized());
                mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + expired)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("expired@example.com")))
                                .andExpect(status().isUnauthorized());
                assertEquals(1, userRepository.count());
        }

        @Test
        void protectedUserCreationRejectsTokenWithCrossTenantClaim() throws Exception {
                OrganizationRegistrationResponse owner = register("jwt-owner-org", "jwt-owner@example.com");
                OrganizationRegistrationResponse other = register("jwt-other-org", "jwt-other@example.com");
                String mismatchedTenantToken = jwt(
                                owner.admin().id(),
                                other.id(),
                                Instant.now().plusSeconds(300),
                                TEST_SECRET);

                mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + mismatchedTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("cross-tenant@example.com")))
                                .andExpect(status().isUnauthorized());
                assertEquals(2, userRepository.count());
        }

        @Test
        void authenticatedMemberIsForbiddenFromCreatingUsers() throws Exception {
                OrganizationRegistrationResponse registered = register("member-forbidden-org", "admin@example.com");
                Organization organization = organizationRepository.findById(registered.id()).orElseThrow();
                User member = userRepository.saveAndFlush(new User(
                                organization,
                                "Regular Member",
                                "member@example.com",
                                passwordEncoder.encode(TEST_PASSWORD),
                                UserRole.MEMBER));

                mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + jwt(
                                                member.getId(),
                                                registered.id(),
                                                Instant.now().plusSeconds(300),
                                                TEST_SECRET))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("forbidden@example.com")))
                                .andExpect(status().isForbidden());
                assertFalse(userRepository.existsByEmail("forbidden@example.com"));
        }

        @Test
        void adminCanExplicitlyDeactivateAndReactivateMemberWithoutDeletingThem() throws Exception {
                OrganizationRegistrationResponse registered = register("status-org", "status-admin@example.com");
                String adminToken = jwt(
                                registered.admin().id(),
                                registered.id(),
                                Instant.now().plusSeconds(600),
                                TEST_SECRET);
                String createdBody = mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("status-member@example.com")))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString();
                UUID memberId = UUID.fromString(responseString(createdBody, "id"));
                long usersBeforeStatusChange = userRepository.count();
                String statusUrl = "/api/v1/users/" + memberId + "/status";

                mockMvc.perform(put(statusUrl)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DISABLED"))
                                .andExpect(jsonPath("$.passwordHash").doesNotExist());
                assertEquals(UserStatus.DISABLED, userRepository.findById(memberId).orElseThrow().getStatus());
                assertEquals(usersBeforeStatusChange, userRepository.count());

                mockMvc.perform(put(statusUrl)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DISABLED"));

                mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + jwt(
                                                memberId,
                                                registered.id(),
                                                Instant.now().plusSeconds(300),
                                                TEST_SECRET))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("disabled-token@example.com")))
                                .andExpect(status().isUnauthorized());
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("status-member@example.com", TEST_PASSWORD)))
                                .andExpect(status().isUnauthorized());

                mockMvc.perform(put(statusUrl)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":true}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));
                assertEquals(UserStatus.ACTIVE, userRepository.findById(memberId).orElseThrow().getStatus());
                assertEquals(usersBeforeStatusChange, userRepository.count());

                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginJson("status-member@example.com", TEST_PASSWORD)))
                                .andExpect(status().isOk());
        }

        @Test
        void adminCannotChangeMemberStatusInAnotherOrganization() throws Exception {
                OrganizationRegistrationResponse owner = register("status-owner-org", "owner-status@example.com");
                OrganizationRegistrationResponse other = register("status-other-org", "other-status@example.com");
                String ownerToken = jwt(
                                owner.admin().id(),
                                owner.id(),
                                Instant.now().plusSeconds(600),
                                TEST_SECRET);
                String otherToken = jwt(
                                other.admin().id(),
                                other.id(),
                                Instant.now().plusSeconds(600),
                                TEST_SECRET);
                String memberBody = mockMvc.perform(post("/api/v1/users")
                                .header("Authorization", "Bearer " + otherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(addUserJson("other-member@example.com")))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString();
                UUID otherMemberId = UUID.fromString(responseString(memberBody, "id"));

                mockMvc.perform(put("/api/v1/users/" + otherMemberId + "/status")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.detail").value("Member not found"));
                assertEquals(UserStatus.ACTIVE, userRepository.findById(otherMemberId).orElseThrow().getStatus());
        }

        @Test
        void statusEndpointRejectsMissingAuthenticationNonAdminAndInvalidBoolean() throws Exception {
                OrganizationRegistrationResponse registered = register("status-auth-org", "status-auth@example.com");
                Organization organization = organizationRepository.findById(registered.id()).orElseThrow();
                User member = userRepository.saveAndFlush(new User(
                                organization,
                                "Status Member",
                                "status-member-auth@example.com",
                                passwordEncoder.encode(TEST_PASSWORD),
                                UserRole.MEMBER));
                String url = "/api/v1/users/" + member.getId() + "/status";

                mockMvc.perform(put(url)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                                .andExpect(status().isUnauthorized());
                mockMvc.perform(put(url)
                                .header("Authorization", "Bearer " + jwt(
                                                member.getId(),
                                                registered.id(),
                                                Instant.now().plusSeconds(300),
                                                TEST_SECRET))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                                .andExpect(status().isForbidden());
                mockMvc.perform(put(url)
                                .header("Authorization", "Bearer " + jwt(
                                                registered.admin().id(),
                                                registered.id(),
                                                Instant.now().plusSeconds(300),
                                                TEST_SECRET))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isBadRequest());
                assertEquals(UserStatus.ACTIVE, userRepository.findById(member.getId()).orElseThrow().getStatus());
        }

        private OrganizationRegistrationResponse register(String slug, String email) {
                return registrationService.register(new OrganizationRegistrationRequest(
                                "Test Organization",
                                slug,
                                "Test Admin",
                                email,
                                TEST_PASSWORD));
        }

        private static String jwt(UUID userId, UUID organizationId, Instant expiresAt, String secret) {
                Instant issuedAt = Instant.now().minusSeconds(1);
                return Jwts.builder()
                                .issuer(ISSUER)
                                .audience().add(AUDIENCE).and()
                                .issuedAt(Date.from(issuedAt))
                                .expiration(Date.from(expiresAt))
                                .claim("userid", userId.toString())
                                .claim("organizationid", organizationId.toString())
                                .claim("role", UserRole.ADMIN.name())
                                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),
                                                Jwts.SIG.HS256)
                                .compact();
        }

        private static String addUserJson(String email) {
                return """
                                {
                                  "name": "New Member",
                                  "email": "%s",
                                  "password": "A-strong-password-123"
                                }
                                """.formatted(email);
        }

        private String loginJson(String email, String password) {
                return """
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password);
        }

        private static String responseString(String json, String field) {
                String key = "\"" + field + "\":\"";
                int valueStart = json.indexOf(key);
                if (valueStart < 0) {
                        throw new AssertionError("Response is missing string field " + field);
                }
                valueStart += key.length();
                int valueEnd = json.indexOf('"', valueStart);
                if (valueEnd < 0) {
                        throw new AssertionError("Response field is not a complete string: " + field);
                }
                return json.substring(valueStart, valueEnd);
        }

        private static String sha256Hex(String value) throws Exception {
                return HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        }
}
