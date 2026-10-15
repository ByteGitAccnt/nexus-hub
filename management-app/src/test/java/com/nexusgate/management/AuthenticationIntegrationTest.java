package com.nexusgate.management;

import com.nexusgate.management.dto.LoginRequest;
import com.nexusgate.management.dto.OrganizationRegistrationRequest;
import com.nexusgate.management.dto.OrganizationRegistrationResponse;
import com.nexusgate.management.model.RefreshToken;
import com.nexusgate.management.repository.OrganizationRepository;
import com.nexusgate.management.repository.RefreshTokenRepository;
import com.nexusgate.management.repository.UserRepository;
import com.nexusgate.management.service.AuthenticationService;
import com.nexusgate.management.service.OrganizationRegistrationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

        private OrganizationRegistrationResponse register(String slug, String email) {
                return registrationService.register(new OrganizationRegistrationRequest(
                                "Test Organization",
                                slug,
                                "Test Admin",
                                email,
                                TEST_PASSWORD));
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
