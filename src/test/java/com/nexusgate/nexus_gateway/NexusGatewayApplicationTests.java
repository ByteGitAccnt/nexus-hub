package com.nexusgate.nexus_gateway;

import com.nexusgate.nexus_gateway.Config.Monitoring.MonitoringConfig;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.NexusRouteBuilder;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtTokenVerifier;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import com.nexusgate.nexus_gateway.Config.ServiceAuthMode;
import com.nexusgate.nexus_gateway.Config.ServiceConfig;
import com.nexusgate.nexus_gateway.Filter.ApiKeyAuthenticationGatewayFilterFactory;
import com.nexusgate.nexus_gateway.Filter.JwtAuthenticationGatewayFilterFactory;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NexusGatewayApplicationTests {

    private final int API_LOOKUP_PREFIX_LENGTH = 20;

    @Mock
    NexusConfig nexusConfig;
    @Mock
    WebClient webClient;

    private static MockWebServer server;

    @BeforeAll
    static void startMockServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        server.shutdown();
    }

    @Test
    void nexusHubRouteStripsOnePrefixSegmentAndAddsConfiguredAuthFilters() {
        NexusConfig config = routeConfig(true);
        ServiceConfig nexusHub = service("/nexushub/**", ServiceAuthMode.API_KEY);
        nexusHub.setPublicEndpoints(List.of("/api/v1/auth/login"));
        config.setServices(Map.of("nexus-hub", nexusHub));

        RouteDefinition route = new NexusRouteBuilder(config)
                .getRouteDefinitions()
                .collectList()
                .block()
                .getFirst();

        assertEquals(URI.create("http://localhost:8090"), route.getUri());
        assertEquals("/nexushub/**", route.getPredicates().getFirst().getArgs().get("pattern"));
        List<FilterDefinition> filters = route.getFilters();
        assertEquals(List.of("JwtAuthentication", "ApiKeyAuthentication", "StripPrefix"),
                filters.stream().map(FilterDefinition::getName).toList());
        assertEquals("API_KEY", filters.getFirst().getArgs().get("authMode"));
        assertEquals("1", filters.getLast().getArgs().get("parts"));
    }

    @Test
    void omittedAuthModeDefaultsToJwtAndExistingStripPrefixStillWorks() {
        NexusConfig config = routeConfig(true);
        config.setServices(Map.of("nexus-hub", service("/nexushub/**", null)));

        RouteDefinition route = new NexusRouteBuilder(config)
                .getRouteDefinitions()
                .collectList()
                .block()
                .getFirst();

        assertEquals("JWT", route.getFilters().getFirst().getArgs().get("authMode"));
        assertEquals("1", route.getFilters().getLast().getArgs().get("parts"));
    }

    @Test
    void publicEndpointAllowsRequestWithoutBearerToken() {
        AtomicBoolean forwarded = new AtomicBoolean();
        var exchange = exchange("/nexushub/api/v1/auth/login");

        runJwt("JWT", List.of("/api/v1/auth/login"), (token) -> invalidToken())
                .filter(exchange, chain(forwarded))
                .block();

        assertTrue(forwarded.get());
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void publicEndpointValidatesSuppliedBearerAndRejectsInvalidBearer() {
        AtomicBoolean validForwarded = new AtomicBoolean();
        var validExchange = exchange("/nexushub/api/v1/auth/login", "Authorization", "Bearer valid");
        runJwt("JWT", List.of("/api/v1/auth/login"), this::validClaims)
                .filter(validExchange, chain(validForwarded))
                .block();
        assertTrue(validForwarded.get());

        AtomicBoolean invalidForwarded = new AtomicBoolean();
        var invalidExchange = exchange("/nexushub/api/v1/auth/login", "Authorization", "Bearer invalid");
        runJwt("JWT", List.of("/api/v1/auth/login"), (token) -> invalidToken())
                .filter(invalidExchange, chain(invalidForwarded))
                .block();
        assertFalse(invalidForwarded.get());
        assertEquals(HttpStatus.UNAUTHORIZED, invalidExchange.getResponse().getStatusCode());
    }

    @Test
    void protectedJwtRouteRequiresAndValidatesBearerToken() {
        assertRejected("/nexushub/api/private", "JWT", null, null);

        AtomicBoolean forwarded = new AtomicBoolean();
        var validExchange = exchange("/nexushub/api/private", "Authorization", "Bearer valid");
        runJwt("JWT", List.of(), this::validClaims)
                .filter(validExchange, chain(forwarded))
                .block();
        assertTrue(forwarded.get());

        assertRejected("/nexushub/api/private", "JWT", "Authorization", "Bearer invalid");
        assertRejected("/nexushub/api/private", "JWT", "Authorization", "Basic token");
    }

    @Test
    void apiKeyModeDelegatesOnlyWithApiKeyAndRejectsUntilValidationExists() {
        AtomicBoolean delegated = new AtomicBoolean();
        var exchange = exchange("/nexushub/api/private", "X-API-Key", "not-validated");
        var jwtFilter = runJwt("API_KEY", List.of(), (token) -> invalidToken());
        var apiKeyFilter = new ApiKeyAuthenticationGatewayFilterFactory(nexusConfig , API_LOOKUP_PREFIX_LENGTH,webClient).apply(
                apiKeyConfig(List.of(), "/nexushub"));
        GatewayFilterChain delegationChain = currentExchange -> {
            delegated.set(true);
            return apiKeyFilter.filter(currentExchange, chain(new AtomicBoolean()));
        };

        jwtFilter.filter(exchange, delegationChain).block();

        assertTrue(delegated.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void apiKeyModeRejectsMissingOrUnsupportedCredentialsWithoutDelegation() {
        assertRejected("/nexushub/api/private", "API_KEY", null, null);
        assertRejected("/nexushub/api/private", "API_KEY", "Authorization", "Basic token");
    }

    @Test
    void validJwtIsAcceptedForApiKeyModeAndPublicServiceAllowsAnonymousAccess() {
        AtomicBoolean jwtForwarded = new AtomicBoolean();
        var jwtExchange = exchange("/nexushub/api/private", "Authorization", "Bearer valid");
        var jwtFilter = runJwt("API_KEY", List.of(), this::validClaims);
        var apiKeyFilter = new ApiKeyAuthenticationGatewayFilterFactory(nexusConfig , API_LOOKUP_PREFIX_LENGTH,webClient).apply(apiKeyConfig(List.of(), "/nexushub"));
        jwtFilter.filter(jwtExchange, current -> apiKeyFilter.filter(current, chain(jwtForwarded))).block();
        assertTrue(jwtForwarded.get());

        AtomicBoolean publicForwarded = new AtomicBoolean();
        var publicExchange = exchange("/nexushub/api/private");
        runJwt("PUBLIC", List.of(), (token) -> invalidToken())
                .filter(publicExchange, chain(publicForwarded))
                .block();
        assertTrue(publicForwarded.get());
    }

    @Test
    void unsupportedAuthModeAndApiKeyOnPublicServiceAreRejected() {
        assertRejected("/nexushub/api/private", "OTHER", null, null);
        assertRejected("/nexushub/api/private", "PUBLIC", "X-API-Key", "key");
    }
    @Test
    void invalidApiKeyIsRejectedByManagementValidation() throws Exception {
        NexusConfig nexusConfig = routeConfig(true);
        // 1. Tell MockWebServer what Management App should return.
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""
                {
                  "valid": false,
                  "organizationId": null,
                  "service": null
                }
                """)
        );

        // 2. Redirect the Management App URL to MockWebServer.
        String mockUrl = server.url("/").toString();
        mockUrl = mockUrl.substring(0, mockUrl.length() - 1);
        ServiceConfig nexusHub = new ServiceConfig();
        nexusHub.setUrl(mockUrl);
        nexusConfig.setServices(Map.of("nexus-hub", nexusHub));

        WebClient testWebClient = WebClient.builder().build();

        // 3. Create a fake incoming request containing an API key.
        var exchange = exchange(
                "/nexushub/api/private",
                "X-API-Key",
                "test-api-key-123-for-looking-up-in-management-app"
        );

        AtomicBoolean delegated = new AtomicBoolean(false);
        AtomicBoolean apiKeyForwarded = new AtomicBoolean(false);

        // 4. Create both filters.
        var jwtFilter = runJwt(
                "API_KEY",
                List.of(),
                token -> invalidToken()
        );

        var apiKeyFilter =
                new ApiKeyAuthenticationGatewayFilterFactory(
                        nexusConfig,
                        API_LOOKUP_PREFIX_LENGTH,
                        testWebClient
                ).apply(apiKeyConfig(List.of(), "/nexushub"));

        // 5. When JWT delegates, execute the API-key filter next.
        GatewayFilterChain delegationChain = new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange currentExchange) {
                delegated.set(true);
                return apiKeyFilter.filter(
                        currentExchange,
                        chain(apiKeyForwarded)
                );
            }
        };


        // 6. Execute the complete filter sequence.
        jwtFilter.filter(exchange, delegationChain).block();
        assertTrue(delegated.get(), "JWT filter did not delegate");

        // 7. Verify the JWT filter delegated to API-key authentication.
        assertTrue(delegated.get());


        // 9. Verify that the Management App validation request was sent.
        var request = server.takeRequest(2, TimeUnit.SECONDS);

        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals(
                "/api/v1/api-keys/internal/validate",
                request.getPath()
        );

        // 10. Verify the validation request body.
        String requestBody = request.getBody().readUtf8();

        assertTrue(requestBody.contains("test-api-key-123"));
        assertTrue(requestBody.contains("/nexushub/api/private"));
    }
    @Test
    void validApiKeyIsAcceptedByManagementValidation() throws Exception {
        NexusConfig nexusConfig = routeConfig(true);
        // 1. Tell MockWebServer what Management App should return.
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""
{
  "valid": true,
  "organizationId": "550e8400-e29b-41d4-a716-446655440000",
  "service": {
    "serviceId": "660e8400-e29b-41d4-a716-446655440000",
    "serviceName": "Test Service",
    "serviceTargetUrl": "http://localhost:8083",
    "serviceBasePath": "/nexushub/api/private",
    "serviceTargetPath": "/api/private"
  }
}
"""));
        // 2. Redirect the Management App URL to MockWebServer.
        String mockUrl = server.url("/").toString();
        mockUrl = mockUrl.substring(0, mockUrl.length() - 1);
        ServiceConfig nexusHub = new ServiceConfig();
        nexusHub.setUrl(mockUrl);
        nexusConfig.setServices(Map.of("nexus-hub", nexusHub));

        WebClient testWebClient = WebClient.builder().build();

        // 3. Create a fake incoming request containing an API key.
        var exchange = exchange(
                "/nexushub/api/private",
                "X-API-Key",
                "test-api-key-123-for-looking-up-in-management-app"
        );

        AtomicBoolean delegated = new AtomicBoolean(false);
        AtomicBoolean apiKeyForwarded = new AtomicBoolean(false);

        // 4. Create both filters.
        var jwtFilter = runJwt(
                "API_KEY",
                List.of(),
                token -> invalidToken()
        );


        var apiKeyFilter =
                new ApiKeyAuthenticationGatewayFilterFactory(
                        nexusConfig,
                        API_LOOKUP_PREFIX_LENGTH,
                        testWebClient
                ).apply(apiKeyConfig(List.of(), "/nexushub"));

        // 5. When JWT delegates, execute the API-key filter next.
        GatewayFilterChain delegationChain = currentExchange -> {
            delegated.set(true);
            return apiKeyFilter.filter(
                    currentExchange,
                    chain(apiKeyForwarded)
            );
        };

        // 6. Execute the complete filter sequence.
        jwtFilter.filter(exchange, delegationChain).block();

        // 7. Verify the JWT filter delegated to API-key authentication.
        assertTrue(delegated.get());


        // 9. Verify that the Management App validation request was sent.
        var request = server.takeRequest(2, TimeUnit.SECONDS);

        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals(
                "/api/v1/api-keys/internal/validate",
                request.getPath()
        );

        // 10. Verify the validation request body.
        String requestBody = request.getBody().readUtf8();

        assertTrue(requestBody.contains("test-api-key-123-for-looking-up-in-management-app"));
        assertTrue(requestBody.contains("/nexushub/api/private"));
        assertTrue(apiKeyForwarded.get());
        assertNull(exchange.getResponse().getStatusCode());
    }
    private void assertRejected(String path, String mode, String header, String value) {
        AtomicBoolean forwarded = new AtomicBoolean();
        MockServerWebExchange exchange = header == null
                ? exchange(path)
                : exchange(path, header, value);
        runJwt(mode, List.of(), token -> invalidToken())
                .filter(exchange, chain(forwarded))
                .block();
        assertFalse(forwarded.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    private GatewayFilter runJwt(
            String authMode,
            List<String> publicEndpoints,
            JwtTokenVerifier verifier
    ) {
        NexusConfig nexusConfig = new NexusConfig();
        MonitoringConfig monitoring = new MonitoringConfig();
        nexusConfig.setMonitoring(monitoring);
        JwtAuthenticationGatewayFilterFactory factory = new JwtAuthenticationGatewayFilterFactory(
                nexusConfig,
                verifier,
                new SimpleMeterRegistry());
        JwtAuthenticationGatewayFilterFactory.Config config = new JwtAuthenticationGatewayFilterFactory.Config();
        config.setServicePath("/nexushub");
        config.setIdentityClaim("userid");
        config.setAuthMode(authMode);
        config.setPublicEndpoints(publicEndpoints);
        return factory.apply(config);
    }
    private Claims validClaims(String token) {
        return Jwts.claims().add("userid", "user-1").build();
    }

    private static Claims invalidToken() {
        throw new MalformedJwtException("Invalid test JWT");
    }

    private static ApiKeyAuthenticationGatewayFilterFactory.Config apiKeyConfig(
            List<String> publicEndpoints,
            String servicePath
    ) {
        ApiKeyAuthenticationGatewayFilterFactory.Config config =
                new ApiKeyAuthenticationGatewayFilterFactory.Config();
        config.setPublicEndpoints(publicEndpoints);
        config.setServicePath(servicePath);
        return config;
    }

    private static GatewayFilterChain chain(AtomicBoolean forwarded) {
        return exchange -> {
            forwarded.set(true);
            return Mono.empty();
        };
    }

    private static MockServerWebExchange exchange(String path, String... header) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (header.length == 2) {
            request.header(header[0], header[1]);
        }
        return MockServerWebExchange.from(request.build());
    }

    private static NexusConfig routeConfig(boolean securityEnabled) {
        NexusConfig config = new NexusConfig();
        SecurityConfig security = new SecurityConfig();
        security.setEnabled(securityEnabled);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setIdentityClaim("userid");
        security.setJwt(jwtConfig);
        config.setSecurity(security);
        config.setRateLimit(new RateLimitConfig());
        return config;
    }

    private static ServiceConfig service(String path, ServiceAuthMode mode) {
        ServiceConfig service = new ServiceConfig();
        service.setUrl("http://localhost:8090");
        service.setPath(path);
        service.setAuthMode(mode);
        return service;
    }
}
