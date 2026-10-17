package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtTokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(
        name = "security.enabled",
        havingValue = "true"
)
public class JwtAuthenticationGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtAuthenticationGatewayFilterFactory.Config> {

    private NexusConfig nexusConfig;
    private final JwtTokenVerifier tokenVerifier;
    private final MeterRegistry meterRegistry;


    public JwtAuthenticationGatewayFilterFactory(NexusConfig nexusConfig, JwtTokenVerifier tokenVerifier, MeterRegistry meterRegistry) {
        super(Config.class);
        this.nexusConfig = nexusConfig;
        this.tokenVerifier = tokenVerifier;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter =  (exchange, chain) -> {

            String requestPath = exchange.getRequest().getPath().value();
            List<String> publicEndpoints =
                    config.getPublicEndpoints() == null
                            ? List.of()
                            : config.getPublicEndpoints();
            String relativePath = requestPath.startsWith(config.getServicePath())
                    ? requestPath.substring(config.getServicePath().length())
                    : requestPath;

            boolean isPublic = publicEndpoints.stream()
                    .anyMatch(relativePath::equals);
            String authMode = config.getAuthMode() == null
                    ? "JWT"
                    : config.getAuthMode().toUpperCase(Locale.ROOT);
            if (!List.of("JWT", "API_KEY", "PUBLIC").contains(authMode)) {
                return reject(exchange, "unsupported_auth_mode");
            }

            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst("Authorization");
            String apiKeyHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-API-Key");

            if (isPublic && authHeader == null) {
                return chain.filter(exchange);
            }

            if (authHeader == null) {
                if ("PUBLIC".equals(authMode) && (apiKeyHeader == null || apiKeyHeader.isBlank())) {
                    return chain.filter(exchange);
                }
                if ("API_KEY".equals(authMode) && apiKeyHeader != null && !apiKeyHeader.isBlank()) {
                    return chain.filter(exchange);
                }
                return reject(exchange, "missing_or_unsupported_credentials");
            }

            if (!authHeader.startsWith("Bearer ") || authHeader.length() == 7) {
                return reject(exchange, "missing_or_invalid_header");
            }

            String jwtToken = authHeader.substring(7);
            try {
                Claims claims = tokenVerifier.verify(jwtToken);
                // Resolve the configured identity claim
                String identityClaim = config.getIdentityClaim();

                Object identity = claims.get(identityClaim);
                if (identity == null) {
                    return reject(exchange, "identity_missing");
                }
                // Authentication will be established here.
                Authentication authentication =
                        new UsernamePasswordAuthenticationToken(
                                identity,
                                null,
                                List.of()
                        );
                // Propagate verified principle downstream
                ServerHttpRequest request = exchange.getRequest().mutate()
                        .headers(headers -> headers.remove("X-Principal"))
                        .header("X-Principal",  String.valueOf(identity))
                        .build();
                exchange = exchange.mutate().request(request).build();

                return chain.filter(exchange)
                        .contextWrite(
                                ReactiveSecurityContextHolder.withAuthentication(
                                        authentication
                                )
                        );
            } catch (JwtException | IllegalArgumentException e) {
                return reject(exchange, "invalid_token");
            }
        };
        //default order is -100, so we set it to -90 to ensure it runs after the default filters
        return new OrderedGatewayFilter(filter, -90);
    }

    private reactor.core.publisher.Mono<Void> reject(
            org.springframework.web.server.ServerWebExchange exchange,
            String reason
    ) {
        if (nexusConfig.getMonitoring().isEnabled()) {
            meterRegistry.counter("nexusgate.auth.failures", "reason", reason).increment();
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Getter
    @Setter
    public static class Config {
        private List<String> publicEndpoints;
        private String servicePath;
        private String identityClaim;
        private String authMode;
    }
}
