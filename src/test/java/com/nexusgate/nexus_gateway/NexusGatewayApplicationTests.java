package com.nexusgate.nexus_gateway;

import com.nexusgate.nexus_gateway.Config.GatewayPathMatcher;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.NexusRouteBuilder;
import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtTokenVerifier;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import com.nexusgate.nexus_gateway.Config.ServiceConfig;
import com.nexusgate.nexus_gateway.Config.Monitoring.MonitoringConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Filter.ApiKeyAuthenticationGatewayFilterFactory;
import com.nexusgate.nexus_gateway.Filter.JwtAuthenticationGatewayFilterFactory;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NexusGatewayApplicationTests {

    private static final UUID API_KEY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORGANIZATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SERVICE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private MockWebServer managementServer;

    @BeforeEach
    void startManagementServer() throws IOException {
        managementServer = new MockWebServer();
        managementServer.start();
    }

    @AfterEach
    void stopManagementServer() throws IOException {
        managementServer.shutdown();
    }

    @Test
    void nexusHubPathsUseJwtOnlyAndConfiguredPublicEndpointsRemainPublic() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.enqueueValidKey();

        assertEquals(HttpStatus.OK, harness.invoke("/nexushub", "Bearer valid", null).status());
        assertEquals(HttpStatus.OK, harness.invoke(
                "/nexushub/organizations", "Bearer valid", "invalid-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke(
                "/nexushub/organizations", null, "valid-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke(
                "/nexushub/organizations", "Bearer invalid", "valid-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke(
                "/nexushub/organizations", null, null).status());

        assertEquals(HttpStatus.OK, harness.invoke("/nexushub/api/v1/auth/login", null, null).status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke(
                "/nexushub/api/v1/auth/login", "Bearer invalid", "valid-key").status());
        assertEquals(4, harness.jwtVerificationCount.get());
        assertEquals(0, harness.managementRequestCount());
        assertEquals(3, harness.downstreamCount.get());
    }

    @Test
    void ordinaryPathsUseApiKeyOnlyAndIgnoreJwtCredential() throws Exception {
        Harness harness = new Harness(Duration.ofSeconds(1));
        for (int i = 0; i < 4; i++) {
            harness.enqueueValidKey();
        }

        assertEquals(HttpStatus.OK, harness.invoke("/orders", null, "valid-key").status());
        assertEquals(HttpStatus.OK, harness.invoke("/orders/items", "Bearer valid", "valid-key").status());
        assertEquals(HttpStatus.OK, harness.invoke("/orders/items", "Bearer invalid", "valid-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/orders", "Bearer valid", null).status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/orders", "Bearer invalid", null).status());
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/orders", null, null).status());
        assertEquals(HttpStatus.OK, harness.invoke("/nexushub-other", null, "valid-key").status());

        assertEquals(0, harness.jwtVerificationCount.get());
        assertEquals(4, harness.managementRequestCount());
        assertEquals(4, harness.downstreamCount.get());
        RecordedRequest validationRequest = managementServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(validationRequest);
        assertEquals("/api/v1/api-keys/internal/validate", validationRequest.getPath());
        assertEquals("internal-test-secret", validationRequest.getHeader("X-Internal-Secret"));
        assertTrue(validationRequest.getBody().readUtf8().contains("\"reqPath\":\"/orders\""));
    }

    @Test
    void invalidPermissionDeniedAndUnavailableValidationNeverForward() throws Exception {
        Harness harness = new Harness(Duration.ofSeconds(1));
        managementServer.enqueue(new MockResponse().setResponseCode(401));
        managementServer.enqueue(new MockResponse().setResponseCode(403));
        managementServer.enqueue(new MockResponse().setResponseCode(500));

        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/orders", null, "invalid").status());
        assertEquals(HttpStatus.FORBIDDEN, harness.invoke("/orders", null, "unauthorized-service").status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, harness.invoke("/orders", null, "key").status());
        assertEquals(0, harness.downstreamCount.get());
        assertEquals(0, harness.jwtVerificationCount.get());
    }

    @Test
    void managementValidationTimeoutFailsClosed() {
        Harness harness = new Harness(Duration.ofMillis(50));
        managementServer.enqueue(new MockResponse()
                .setBody(harness.validResult())
                .setBodyDelay(300, TimeUnit.MILLISECONDS));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                harness.invoke("/orders", null, "valid-key").status());
        assertEquals(0, harness.downstreamCount.get());
        assertEquals(0, harness.jwtVerificationCount.get());
    }

    @Test
    void successfulApiKeyAuthEstablishesContextAndPreservesRequestPath() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.enqueueValidKey();

        ExchangeResult result = harness.invoke("/orders/items", "Bearer invalid", "valid-key");

        assertEquals(HttpStatus.OK, result.status());
        assertEquals("/orders/items", result.downstreamPath());
        assertEquals(API_KEY_ID.toString(), result.principalHeader());
        assertEquals("apikey:" + API_KEY_ID, result.authenticationPrincipal());
        assertEquals(ORGANIZATION_ID.toString(), result.organizationId());
        assertEquals(SERVICE_ID.toString(), result.serviceId());
        assertNull(result.forwardedApiKey());
    }

    @Test
    void pathMatchingUsesSegmentBoundariesAndPublicEndpointExactMatch() {
        assertTrue(GatewayPathMatcher.matchesPathOrDescendant("/nexushub", "/nexushub/**"));
        assertTrue(GatewayPathMatcher.matchesPathOrDescendant(
                "/nexushub/organizations/123", "/nexushub/**"));
        assertFalse(GatewayPathMatcher.matchesPathOrDescendant("/nexushub-other", "/nexushub/**"));
        assertFalse(GatewayPathMatcher.matchesPathOrDescendant("/orders", "/nexushub/**"));
        assertTrue(GatewayPathMatcher.matchesPublicEndpoint(
                "/nexushub/api/v1/auth/login",
                "/nexushub/**",
                List.of("/api/v1/auth/login")));
        assertFalse(GatewayPathMatcher.matchesPublicEndpoint(
                "/nexushub/api/v1/auth/login/extra",
                "/nexushub/**",
                List.of("/api/v1/auth/login")));
    }

    @Test
    void everyConfiguredRouteGetsBothAuthenticationFiltersAndNexusHubKeepsStripPrefix() {
        NexusConfig config = routeConfig();
        List<RouteDefinition> routes = new NexusRouteBuilder(config).getRouteDefinitions().collectList().block();

        assertNotNull(routes);
        assertEquals(2, routes.size());
        RouteDefinition managementRoute = routes.stream()
                .filter(route -> "nexus-hub".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        RouteDefinition apiRoute = routes.stream()
                .filter(route -> "orders".equals(route.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("JwtAuthentication", "ApiKeyAuthentication", "StripPrefix"),
                managementRoute.getFilters().stream().map(filter -> filter.getName()).toList());
        assertEquals(List.of("JwtAuthentication", "ApiKeyAuthentication"),
                apiRoute.getFilters().stream().map(filter -> filter.getName()).toList());
        assertEquals("/nexushub/**",
                managementRoute.getFilters().get(0).getArgs().get("nexusHubPath"));
        assertEquals("/api/v1/auth/login",
                managementRoute.getFilters().get(0).getArgs().get("publicEndpoints"));
    }

    private NexusConfig routeConfig() {
        NexusConfig config = new NexusConfig();
        SecurityConfig security = new SecurityConfig();
        security.setEnabled(true);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setIdentityClaim("userid");
        security.setJwt(jwtConfig);
        config.setSecurity(security);
        config.setMonitoring(new MonitoringConfig());
        config.setRateLimit(new RateLimitConfig());

        ServiceConfig nexusHub = new ServiceConfig();
        nexusHub.setUrl("http://localhost:9000");
        nexusHub.setPath("/nexushub/**");
        nexusHub.setPublicEndpoints(List.of("/api/v1/auth/login"));
        ServiceConfig orders = new ServiceConfig();
        orders.setUrl("http://localhost:9001");
        orders.setPath("/orders/**");
        Map<String, ServiceConfig> services = new HashMap<>();
        services.put("nexus-hub", nexusHub);
        services.put("orders", orders);
        config.setServices(services);
        return config;
    }

    private final class Harness {
        private final NexusConfig config;
        private final AtomicInteger jwtVerificationCount = new AtomicInteger();
        private final AtomicInteger downstreamCount = new AtomicInteger();
        private final JwtAuthenticationGatewayFilterFactory jwtFactory;
        private final ApiKeyAuthenticationGatewayFilterFactory apiKeyFactory;
        private final String baseUrl;

        private Harness(Duration timeout) {
            config = routeConfig();
            config.getServices().get("nexus-hub").setUrl(managementServer.url("/").toString());
            baseUrl = config.getServices().get("nexus-hub").getUrl();

            JwtTokenVerifier verifier = token -> {
                jwtVerificationCount.incrementAndGet();
                if (!"valid".equals(token)) {
                    throw new JwtException("invalid token");
                }
                return Jwts.claims().add("userid", "test-user").build();
            };
            jwtFactory = new JwtAuthenticationGatewayFilterFactory(
                    config, verifier, new SimpleMeterRegistry());
            apiKeyFactory = new ApiKeyAuthenticationGatewayFilterFactory(
                    config, WebClient.builder().build(), "internal-test-secret", timeout);
        }

        private void enqueueValidKey() {
            managementServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(validResult()));
        }

        private String validResult() {
            return """
                    {"valid":true,"apiKeyId":"%s","organizationId":"%s",
                     "service":{"serviceId":"%s","serviceName":"Orders"}}
                    """.formatted(API_KEY_ID, ORGANIZATION_ID, SERVICE_ID);
        }

        private int managementRequestCount() {
            return managementServer.getRequestCount();
        }

        private ExchangeResult invoke(String path, String authorization, String apiKey) {
            MockServerHttpRequest.BaseBuilder<?> requestBuilder = MockServerHttpRequest.get(path)
                    .header("X-Organization-ID", "attacker-controlled-organization");
            if (authorization != null) {
                requestBuilder.header("Authorization", authorization);
            }
            if (apiKey != null) {
                requestBuilder.header("X-API-Key", apiKey);
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
            GatewayFilter jwtFilter = jwtFactory.apply(jwtConfig());
            GatewayFilter apiKeyFilter = apiKeyFactory.apply(apiKeyConfig());
            GatewayFilterChain downstream = currentExchange -> {
                downstreamCount.incrementAndGet();
                currentExchange.getAttributes().put("test.downstreamPath",
                        currentExchange.getRequest().getURI().getPath());
                putAttribute(currentExchange, "test.principalHeader",
                        currentExchange.getRequest().getHeaders().getFirst("X-Principal"));
                putAttribute(currentExchange, "test.organizationId",
                        currentExchange.getRequest().getHeaders().getFirst("X-Organization-ID"));
                putAttribute(currentExchange, "test.serviceId",
                        currentExchange.getRequest().getHeaders().getFirst("X-Service-ID"));
                putAttribute(currentExchange, "test.forwardedApiKey",
                        currentExchange.getRequest().getHeaders().getFirst("X-API-Key"));
                currentExchange.getResponse().setStatusCode(HttpStatus.OK);
                return ReactiveSecurityContextHolder.getContext()
                        .map(securityContext -> securityContext.getAuthentication().getPrincipal())
                        .map(String::valueOf)
                        .doOnNext(principal -> putAttribute(
                                currentExchange, "test.authenticationPrincipal", principal))
                        .then(Mono.defer(currentExchange.getResponse()::setComplete));
            };

            jwtFilter.filter(exchange, current -> apiKeyFilter.filter(current, downstream))
                    .block(Duration.ofSeconds(2));
            return new ExchangeResult(
                    status(exchange.getResponse().getStatusCode()),
                    (String) exchange.getAttributes().get("test.downstreamPath"),
                    (String) exchange.getAttributes().get("test.organizationId"),
                    (String) exchange.getAttributes().get("test.serviceId"),
                    (String) exchange.getAttributes().get("test.principalHeader"),
                    (String) exchange.getAttributes().get("test.authenticationPrincipal"),
                    (String) exchange.getAttributes().get("test.forwardedApiKey"));
        }

        private void putAttribute(
                ServerWebExchange exchange,
                String name,
                String value
        ) {
            if (value != null) {
                exchange.getAttributes().put(name, value);
            }
        }

        private HttpStatus status(HttpStatusCode statusCode) {
            return statusCode == null ? null : HttpStatus.valueOf(statusCode.value());
        }

        private JwtAuthenticationGatewayFilterFactory.Config jwtConfig() {
            JwtAuthenticationGatewayFilterFactory.Config config = new JwtAuthenticationGatewayFilterFactory.Config();
            config.setNexusHubPath("/nexushub/**");
            config.setPublicEndpoints(List.of("/api/v1/auth/login"));
            config.setIdentityClaim("userid");
            return config;
        }

        private ApiKeyAuthenticationGatewayFilterFactory.Config apiKeyConfig() {
            ApiKeyAuthenticationGatewayFilterFactory.Config config =
                    new ApiKeyAuthenticationGatewayFilterFactory.Config();
            config.setNexusHubPath("/nexushub/**");
            return config;
        }
    }

    private record ExchangeResult(
            HttpStatus status,
            String downstreamPath,
            String organizationId,
            String serviceId,
            String principalHeader,
            String authenticationPrincipal,
            String forwardedApiKey
    ) { }
}
