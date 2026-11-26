package com.nexusgate.nexus_gateway;

import com.nexusgate.nexus_gateway.Config.GatewayExchangeAttributes;
import com.nexusgate.nexus_gateway.Config.GatewayPathMatcher;
import com.nexusgate.nexus_gateway.Config.BusinessRouteConfig;
import com.nexusgate.nexus_gateway.Config.Monitoring.MonitoringConfig;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.NexusRouteBuilder;
import com.nexusgate.nexus_gateway.Config.RateLimit.ClientIpResolver;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitKeyGenerator;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitPolicy;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitResult;
import com.nexusgate.nexus_gateway.Config.RateLimit.RedisTokenBucket;
import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtTokenVerifier;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import com.nexusgate.nexus_gateway.Config.ServiceConfig;
import com.nexusgate.nexus_gateway.Config.Logging.LoggingConfig;
import com.nexusgate.nexus_gateway.Dto.ValidationResult;
import com.nexusgate.nexus_gateway.Dto.ValidServiceDetails;
import com.nexusgate.nexus_gateway.Filter.ApiKeyAuthenticationWebFilter;
import com.nexusgate.nexus_gateway.Filter.InnerRateLimitGatewayFilterFactory;
import com.nexusgate.nexus_gateway.Filter.JwtAuthenticationGatewayFilterFactory;
import com.nexusgate.nexus_gateway.Filter.OuterRateLimitWebFilter;
import com.nexusgate.nexus_gateway.Filter.RequestLoggingWebFilter;
import com.nexusgate.nexus_gateway.Filter.ValidatedPathRewriteGatewayFilterFactory;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class NexusGatewayApplicationTests {

    private static final int SPRING_SECURITY_WEBFILTER_ORDER = -100;
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
    void infrastructureFiltersRunBeforeSecurityAndRouteLookupForUnmatchedRequests() throws Exception {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.enqueueValidKey();
        ExchangeResult result = harness.invoke("/unmatched", null, "valid-key");

        assertEquals(HttpStatus.NOT_FOUND, result.status());
        assertEquals(1, harness.managementRequestCount());
        assertEquals(1, harness.routeLookupCount.get());
        assertEquals(0, harness.downstreamCount.get());
        assertNotNull(result.requestId());
        assertNotNull(result.validationResult());
        assertEquals(ORGANIZATION_ID.toString(), result.organizationId());
        assertEquals(SERVICE_ID.toString(), result.serviceId());
        assertEquals(API_KEY_ID.toString(), result.principalHeader());
        assertEquals("apikey:" + API_KEY_ID, result.authenticationPrincipal());
        assertNull(result.forwardedApiKey());
        ArgumentCaptor<String> ipBucket = ArgumentCaptor.forClass(String.class);
        verify(harness.outerBucket).consume(ipBucket.capture(), any(RateLimitPolicy.class));
        assertTrue(ipBucket.getValue().startsWith(RateLimitKeyGenerator.IP_PREFIX));

        RecordedRequest validationRequest = managementServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(validationRequest);
        assertEquals("/api/v1/api-keys/internal/validate", validationRequest.getPath());
        assertEquals("internal-test-secret", validationRequest.getHeader("X-Internal-Secret"));
        assertTrue(validationRequest.getBody().readUtf8().contains("\"reqPath\":\"/unmatched\""));
    }

    @Test
    void outerLimitRunsAfterLoggingAndRejectsBeforeApiKeyValidation() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        NexusConfig config = routeConfig();
        config.getRateLimit().setEnabled(true);
        RateLimitPolicy outerPolicy = new RateLimitPolicy();
        outerPolicy.setEnabled(true);
        outerPolicy.setCapacity(1);
        config.getRateLimit().setOuter(outerPolicy);

        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        given(ipResolver.resolve(any())).willReturn("127.0.0.1");
        RedisTokenBucket tokenBucket = mock(RedisTokenBucket.class);
        given(tokenBucket.consume(anyString(), any(RateLimitPolicy.class)))
                .willReturn(Mono.just(new RateLimitResult(false, 0, 1)));
        OuterRateLimitWebFilter outer = new OuterRateLimitWebFilter(
                ipResolver, new RateLimitKeyGenerator(), tokenBucket, config, new SimpleMeterRegistry());
        RequestLoggingWebFilter logging = new RequestLoggingWebFilter(config, new SimpleMeterRegistry());
        assertTrue(logging.getOrder() < outer.getOrder());
        assertTrue(outer.getOrder() < SPRING_SECURITY_WEBFILTER_ORDER);
        assertTrue(SPRING_SECURITY_WEBFILTER_ORDER < harness.apiKeyFilter.getOrder());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/unmatched").header("X-API-Key", "valid-key").build());
        WebFilterChain routeLookup = requestExchange -> {
            harness.routeLookupCount.incrementAndGet();
            return requestExchange.getResponse().setComplete();
        };
        logging.filter(exchange, requestExchange ->
                outer.filter(requestExchange, requestAfterOuter ->
                        harness.apiKeyFilter.filter(requestAfterOuter, routeLookup)))
                .block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertNotNull(exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        assertEquals(0, harness.managementRequestCount());
        assertEquals(0, harness.routeLookupCount.get());
    }

    @Test
    void invalidKeyStopsBeforeRouteLookupAndUnavailableValidationFailsClosed() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/unmatched", null, null).status());
        assertEquals(0, harness.routeLookupCount.get());

        managementServer.enqueue(new MockResponse().setResponseCode(401));
        assertEquals(HttpStatus.UNAUTHORIZED, harness.invoke("/unmatched", null, "bad-key").status());
        assertEquals(0, harness.routeLookupCount.get());

        managementServer.enqueue(new MockResponse().setResponseCode(500));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                harness.invoke("/unmatched", null, "valid-key").status());
        assertEquals(0, harness.routeLookupCount.get());
        assertEquals(2, harness.managementRequestCount());
    }

    @Test
    void validationTimeoutFailsBeforeRouteLookup() {
        Harness harness = new Harness(Duration.ofMillis(50));
        managementServer.enqueue(new MockResponse()
                .setBody(harness.validResult())
                .setBodyDelay(300, TimeUnit.MILLISECONDS));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                harness.invoke("/unmatched", null, "valid-key").status());
        assertEquals(0, harness.routeLookupCount.get());
    }

    @Test
    void nexusHubSkipsApiKeyValidationAndKeepsJwtPublicEndpointBehavior() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        assertEquals(HttpStatus.OK,
                harness.invoke("/nexushub/api/v1/auth/login", null, null).status());
        assertEquals(HttpStatus.OK,
                harness.invoke("/nexushub/organizations", "Bearer valid", "ignored-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                harness.invoke("/nexushub/organizations", null, "valid-key").status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                harness.invoke("/nexushub/organizations", "Bearer invalid", "valid-key").status());

        assertEquals(0, harness.managementRequestCount());
        assertEquals(2, harness.jwtVerificationCount.get());
        assertEquals(2, harness.downstreamCount.get());
    }

    @Test
    void pathMatchingUsesSegmentBoundariesAndPublicEndpointExactMatch() {
        assertTrue(GatewayPathMatcher.matchesPathOrDescendant("/nexushub", "/nexushub/**"));
        assertTrue(GatewayPathMatcher.matchesPathOrDescendant(
                "/nexushub/organizations/123", "/nexushub/**"));
        assertFalse(GatewayPathMatcher.matchesPathOrDescendant("/nexushub-other", "/nexushub/**"));
        assertFalse(GatewayPathMatcher.matchesPathOrDescendant("/orders", "/nexushub/**"));
        assertTrue(GatewayPathMatcher.matchesPublicEndpoint(
                "/nexushub/api/v1/auth/login", "/nexushub/**", List.of("/api/v1/auth/login")));
        assertFalse(GatewayPathMatcher.matchesPublicEndpoint(
                "/nexushub/api/v1/auth/login/extra", "/nexushub/**", List.of("/api/v1/auth/login")));
    }

    @Test
    void managementRoutePrecedesFailClosedBusinessEntryRoute() {
        NexusConfig config = routeConfig();
        config.getRateLimit().setEnabled(true);
        List<RouteDefinition> routes = new NexusRouteBuilder(config)
                .getRouteDefinitions().collectList().block();
        assertNotNull(routes);
        RouteDefinition managementRoute = routes.stream()
                .filter(route -> "nexus-hub".equals(route.getId()))
                .findFirst().orElseThrow();
        RouteDefinition businessRoute = routes.stream()
                .filter(route -> "business-entry".equals(route.getId()))
                .findFirst().orElseThrow();

        assertEquals(0, managementRoute.getOrder());
        assertEquals(100, businessRoute.getOrder());
        assertEquals("no", businessRoute.getUri().getScheme());
        assertEquals("op", businessRoute.getUri().getHost());
        assertEquals("/nexushub/**", managementRoute.getPredicates().get(0).getArgs().get("pattern"));
        assertEquals("/**", businessRoute.getPredicates().get(0).getArgs().get("pattern"));
        assertEquals(List.of("JwtAuthentication", "InnerRateLimit", "StripPrefix"),
                managementRoute.getFilters().stream().map(filter -> filter.getName()).toList());
        assertEquals(List.of("InnerRateLimit", "ValidatedPathRewrite"),
                businessRoute.getFilters().stream().map(filter -> filter.getName()).toList());

        var pathParser = new PathPatternParser();
        RouteDefinition selectedManagement = routes.stream()
                .filter(route -> matches(route, "/nexushub/api/v1/auth/login", pathParser))
                .min(java.util.Comparator.comparingInt(RouteDefinition::getOrder))
                .orElseThrow();
        assertEquals("nexus-hub", selectedManagement.getId());
        RouteDefinition selectedBusiness = routes.stream()
                .filter(route -> matches(route, "/authentication/auth_test", pathParser))
                .min(java.util.Comparator.comparingInt(RouteDefinition::getOrder))
                .orElseThrow();
        assertEquals("business-entry", selectedBusiness.getId());
    }

    @Test
    void innerLimiterUsesValidatedOrganizationAndServiceFromStoredResult() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.enqueueValidKey();
        RateLimitPolicy innerPolicy = new RateLimitPolicy();
        innerPolicy.setEnabled(true);
        innerPolicy.setCapacity(10);
        harness.config.getRateLimit().setEnabled(true);
        harness.config.getRateLimit().setInner(innerPolicy);
        RedisTokenBucket tokenBucket = mock(RedisTokenBucket.class);
        given(tokenBucket.consume(anyString(), any(RateLimitPolicy.class)))
                .willReturn(Mono.just(new RateLimitResult(true, 9, 0)));
        InnerRateLimitGatewayFilterFactory inner = new InnerRateLimitGatewayFilterFactory(
                new RateLimitKeyGenerator(), tokenBucket, harness.config, new SimpleMeterRegistry());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header("X-API-Key", "valid-key").build());
        GatewayFilter innerFilter = inner.apply(new Object());
        harness.apiKeyFilter.filter(exchange, authorizedExchange ->
                innerFilter.filter(authorizedExchange, routedExchange -> {
                    routedExchange.getResponse().setStatusCode(HttpStatus.OK);
                    return routedExchange.getResponse().setComplete();
                })).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        ValidationResult stored = exchange.getAttribute(GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT);
        assertNotNull(stored);
        assertEquals(ORGANIZATION_ID, stored.organizationId());
        assertEquals(SERVICE_ID, stored.service().serviceId());
        ArgumentCaptor<String> bucketKey = ArgumentCaptor.forClass(String.class);
        verify(tokenBucket).consume(bucketKey.capture(), any(RateLimitPolicy.class));
        assertEquals(new RateLimitKeyGenerator().forApiKey(
                ORGANIZATION_ID.toString(), SERVICE_ID.toString()), bucketKey.getValue());
    }

    @Test
    void validatedRewriteNormalizesPathsPreservesQueryAndRejectsBadMetadata() {
        ValidatedPathRewriteGatewayFilterFactory rewriteFactory =
                new ValidatedPathRewriteGatewayFilterFactory();

        assertRewrite(rewriteFactory, "/authentication", "authentication", "auth/api/v1",
                "/auth/api/v1", null);
        assertRewrite(rewriteFactory, "/authentication/auth_test?mode=full",
                "/authentication/", "/auth/api/v1/", "/auth/api/v1/auth_test", "mode=full");
        assertRewrite(rewriteFactory, "/authentication/nested/path?x=1&y=two",
                "/authentication/", "auth/api/v1", "/auth/api/v1/nested/path", "x=1&y=two");
        assertRewrite(rewriteFactory, "/authentication/auth_test",
                "/authentication", "/auth/api/v1", "/auth/api/v1/auth_test", null);

        MockServerWebExchange missingMetadata = MockServerWebExchange.from(
                MockServerHttpRequest.get("/authentication/auth_test").build());
        AtomicInteger missingMetadataContinuation = new AtomicInteger();
        rewriteFactory.apply(new Object()).filter(missingMetadata, exchange -> {
            missingMetadataContinuation.incrementAndGet();
            return Mono.empty();
        }).block(Duration.ofSeconds(1));
        assertEquals(HttpStatus.UNAUTHORIZED, missingMetadata.getResponse().getStatusCode());
        assertEquals(0, missingMetadataContinuation.get());

        MockServerWebExchange invalidServiceMetadata = authenticatedRewriteExchange(
                "/authentication/auth_test", "/different-service", "/auth/api/v1");
        AtomicInteger invalidMetadataContinuation = new AtomicInteger();
        rewriteFactory.apply(new Object()).filter(invalidServiceMetadata, exchange -> {
            invalidMetadataContinuation.incrementAndGet();
            return Mono.empty();
        }).block(Duration.ofSeconds(1));
        assertEquals(HttpStatus.FORBIDDEN, invalidServiceMetadata.getResponse().getStatusCode());
        assertEquals(0, invalidMetadataContinuation.get());
    }

    @Test
    void authenticatedBusinessRouteConsumesInnerLimitThenRewritesWithoutForwarding() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.enqueueValidKey();
        RateLimitPolicy innerPolicy = new RateLimitPolicy();
        innerPolicy.setEnabled(true);
        innerPolicy.setCapacity(10);
        harness.config.getRateLimit().setEnabled(true);
        harness.config.getRateLimit().setInner(innerPolicy);
        RedisTokenBucket tokenBucket = mock(RedisTokenBucket.class);
        given(tokenBucket.consume(anyString(), any(RateLimitPolicy.class)))
                .willReturn(Mono.just(new RateLimitResult(true, 9, 0)));
        InnerRateLimitGatewayFilterFactory inner = new InnerRateLimitGatewayFilterFactory(
                new RateLimitKeyGenerator(), tokenBucket, harness.config, new SimpleMeterRegistry());
        GatewayFilter innerFilter = inner.apply(new Object());
        GatewayFilter rewriteFilter = new ValidatedPathRewriteGatewayFilterFactory().apply(new Object());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders/invoices?id=5")
                        .header("X-API-Key", "valid-key").build());
        Route businessRoute = mock(Route.class);
        given(businessRoute.getId()).willReturn("business-entry");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, businessRoute);

        harness.apiKeyFilter.filter(exchange, authorizedExchange ->
                innerFilter.filter(authorizedExchange, rateLimitedExchange ->
                        rewriteFilter.filter(rateLimitedExchange, rewrittenExchange -> {
                            assertEquals("/auth/api/v1/invoices",
                                    rewrittenExchange.getRequest().getPath().value());
                            assertEquals("id=5", rewrittenExchange.getRequest().getURI().getRawQuery());
                            rewrittenExchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                            return rewrittenExchange.getResponse().setComplete();
                        }))).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.NO_CONTENT, exchange.getResponse().getStatusCode());
        ArgumentCaptor<String> bucketKey = ArgumentCaptor.forClass(String.class);
        verify(tokenBucket).consume(bucketKey.capture(), any(RateLimitPolicy.class));
        assertEquals(new RateLimitKeyGenerator().forApiKey(
                ORGANIZATION_ID.toString(), SERVICE_ID.toString()), bucketKey.getValue());
        assertEquals(1, harness.managementRequestCount());
    }

    @Test
    void businessEntryInnerLimiterRejectsMissingAuthenticationMetadata() {
        Harness harness = new Harness(Duration.ofSeconds(1));
        harness.config.getRateLimit().setEnabled(true);
        RateLimitPolicy innerPolicy = new RateLimitPolicy();
        innerPolicy.setEnabled(true);
        harness.config.getRateLimit().setInner(innerPolicy);
        RedisTokenBucket tokenBucket = mock(RedisTokenBucket.class);
        InnerRateLimitGatewayFilterFactory inner = new InnerRateLimitGatewayFilterFactory(
                new RateLimitKeyGenerator(), tokenBucket, harness.config, new SimpleMeterRegistry());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/authentication/auth_test").build());
        Route businessRoute = mock(Route.class);
        given(businessRoute.getId()).willReturn("business-entry");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, businessRoute);
        AtomicInteger continuationCount = new AtomicInteger();

        inner.apply(new Object()).filter(exchange, ignored -> {
            continuationCount.incrementAndGet();
            return Mono.empty();
        }).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(0, continuationCount.get());
        verifyNoInteractions(tokenBucket);
    }

    private static boolean matches(RouteDefinition route, String path, PathPatternParser parser) {
        String pattern = route.getPredicates().get(0).getArgs().get("pattern");
        return parser.parse(pattern).matches(PathContainer.parsePath(path));
    }

    private static void assertRewrite(
            ValidatedPathRewriteGatewayFilterFactory factory,
            String requestUri,
            String basePath,
            String targetPath,
            String expectedPath,
            String expectedQuery
    ) {
        MockServerWebExchange exchange = authenticatedRewriteExchange(requestUri, basePath, targetPath);
        AtomicInteger continuationCount = new AtomicInteger();
        factory.apply(new Object()).filter(exchange, rewrittenExchange -> {
            continuationCount.incrementAndGet();
            assertEquals(expectedPath, rewrittenExchange.getRequest().getPath().value());
            assertEquals(expectedQuery, rewrittenExchange.getRequest().getURI().getRawQuery());
            return Mono.empty();
        }).block(Duration.ofSeconds(1));
        assertEquals(1, continuationCount.get());
    }

    private static MockServerWebExchange authenticatedRewriteExchange(
            String requestUri,
            String basePath,
            String targetPath
    ) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(requestUri).build());
        exchange.getAttributes().put(GatewayExchangeAttributes.AUTH_MODE, "API_KEY");
        exchange.getAttributes().put(GatewayExchangeAttributes.ORGANIZATION_ID, ORGANIZATION_ID.toString());
        exchange.getAttributes().put(GatewayExchangeAttributes.SERVICE_ID, SERVICE_ID.toString());
        exchange.getAttributes().put(GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT,
                new ValidationResult(true, API_KEY_ID, ORGANIZATION_ID,
                        new ValidServiceDetails(SERVICE_ID, "Authentication", null, basePath, targetPath)));
        return exchange;
    }

    private static NexusConfig routeConfig() {
        NexusConfig config = new NexusConfig();
        BusinessRouteConfig businessRoute = new BusinessRouteConfig();
        businessRoute.setEnabled(true);
        businessRoute.setPath("/**");
        businessRoute.setOrder(100);
        config.setBusinessRoute(businessRoute);
        SecurityConfig security = new SecurityConfig();
        security.setEnabled(true);
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setIdentityClaim("userid");
        security.setJwt(jwtConfig);
        config.setSecurity(security);
        config.setMonitoring(new MonitoringConfig());
        config.setRateLimit(new RateLimitConfig());
        LoggingConfig logging = new LoggingConfig();
        logging.setEnabled(true);
        config.setLogging(logging);

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
        private final AtomicInteger routeLookupCount = new AtomicInteger();
        private final JwtAuthenticationGatewayFilterFactory jwtFactory;
        private final ApiKeyAuthenticationWebFilter apiKeyFilter;
        private final RedisTokenBucket outerBucket;
        private final RequestLoggingWebFilter loggingFilter;
        private final OuterRateLimitWebFilter outerFilter;

        private Harness(Duration timeout) {
            config = routeConfig();
            config.getServices().get("nexus-hub").setUrl(managementServer.url("/").toString());
            config.getRateLimit().setEnabled(true);
            RateLimitPolicy outerPolicy = new RateLimitPolicy();
            outerPolicy.setEnabled(true);
            outerPolicy.setCapacity(100);
            outerPolicy.setRefillRate(100);
            outerPolicy.setRefillPeriodSeconds(60);
            config.getRateLimit().setOuter(outerPolicy);
            JwtTokenVerifier verifier = token -> {
                jwtVerificationCount.incrementAndGet();
                if (!"valid".equals(token)) {
                    throw new JwtException("invalid token");
                }
                return Jwts.claims().add("userid", "test-user").build();
            };
            jwtFactory = new JwtAuthenticationGatewayFilterFactory(
                    config, verifier, new SimpleMeterRegistry());
            apiKeyFilter = new ApiKeyAuthenticationWebFilter(
                    config, WebClient.builder().build(), "internal-test-secret", timeout);
            ClientIpResolver ipResolver = mock(ClientIpResolver.class);
            given(ipResolver.resolve(any())).willReturn("127.0.0.1");
            outerBucket = mock(RedisTokenBucket.class);
            given(outerBucket.consume(anyString(), any(RateLimitPolicy.class)))
                    .willReturn(Mono.just(new RateLimitResult(true, 99, 0)));
            outerFilter = new OuterRateLimitWebFilter(
                    ipResolver, new RateLimitKeyGenerator(), outerBucket, config,
                    new SimpleMeterRegistry());
            loggingFilter = new RequestLoggingWebFilter(config, new SimpleMeterRegistry());
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
                     "service":{"serviceId":"%s","serviceName":"Orders",
                     "serviceBasePath":"/orders","serviceTargetPath":"/auth/api/v1"}}
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
            WebFilterChain routeLookup = currentExchange -> {
                routeLookupCount.incrementAndGet();
                if (GatewayPathMatcher.matchesPathOrDescendant(
                        currentExchange.getRequest().getPath().value(), "/nexushub/**")) {
                    GatewayFilter jwtFilter = jwtFactory.apply(jwtConfig());
                    GatewayFilterChain managementRoute = managementExchange -> {
                        downstreamCount.incrementAndGet();
                        managementExchange.getResponse().setStatusCode(HttpStatus.OK);
                        return managementExchange.getResponse().setComplete();
                    };
                    return jwtFilter.filter(currentExchange, managementRoute);
                }
                ValidationResult validationResult = currentExchange.getAttribute(
                        GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT);
                if (validationResult == null) {
                    currentExchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return currentExchange.getResponse().setComplete();
                }
                putAttribute(currentExchange, "test.principalHeader",
                        currentExchange.getRequest().getHeaders().getFirst("X-Principal"));
                putAttribute(currentExchange, "test.organizationId",
                        currentExchange.getRequest().getHeaders().getFirst("X-Organization-ID"));
                putAttribute(currentExchange, "test.serviceId",
                        currentExchange.getRequest().getHeaders().getFirst("X-Service-ID"));
                putAttribute(currentExchange, "test.forwardedApiKey",
                        currentExchange.getRequest().getHeaders().getFirst("X-API-Key"));
                return ReactiveSecurityContextHolder.getContext()
                        .map(context -> context.getAuthentication().getPrincipal().toString())
                        .doOnNext(principal -> currentExchange.getAttributes()
                                .put("test.authenticationPrincipal", principal))
                        .then(Mono.defer(() -> {
                            currentExchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                            return currentExchange.getResponse().setComplete();
                        }));
            };

            loggingFilter.filter(exchange, loggedExchange ->
                    outerFilter.filter(loggedExchange, rateLimitedExchange ->
                            apiKeyFilter.filter(rateLimitedExchange, routeLookup)))
                    .block(Duration.ofSeconds(2));
            ValidationResult result = exchange.getAttribute(
                    GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT);
            return new ExchangeResult(
                    status(exchange.getResponse().getStatusCode()),
                    (String) exchange.getAttributes().get("test.principalHeader"),
                    (String) exchange.getAttributes().get("test.organizationId"),
                    (String) exchange.getAttributes().get("test.serviceId"),
                    (String) exchange.getAttributes().get("test.forwardedApiKey"),
                    (String) exchange.getAttributes().get("test.authenticationPrincipal"),
                    exchange.getResponse().getHeaders().getFirst("X-Request-ID"),
                    result);
        }

        private void putAttribute(ServerWebExchange exchange, String name, String value) {
            if (value != null) {
                exchange.getAttributes().put(name, value);
            }
        }

        private JwtAuthenticationGatewayFilterFactory.Config jwtConfig() {
            JwtAuthenticationGatewayFilterFactory.Config config = new JwtAuthenticationGatewayFilterFactory.Config();
            config.setNexusHubPath("/nexushub/**");
            config.setPublicEndpoints(List.of("/api/v1/auth/login"));
            config.setIdentityClaim("userid");
            return config;
        }
    }

    private static HttpStatus status(HttpStatusCode statusCode) {
        return statusCode == null ? null : HttpStatus.valueOf(statusCode.value());
    }

    private record ExchangeResult(
            HttpStatus status,
            String principalHeader,
            String organizationId,
            String serviceId,
            String forwardedApiKey,
            String authenticationPrincipal,
            String requestId,
            ValidationResult validationResult) {
    }
}
