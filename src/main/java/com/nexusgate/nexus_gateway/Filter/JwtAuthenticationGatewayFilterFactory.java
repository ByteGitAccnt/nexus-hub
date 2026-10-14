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

            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst("Authorization");

            //skips the auth verification
            if (isPublic && authHeader == null) {
                return chain.filter(exchange);
            }

            if ( authHeader == null || !authHeader.startsWith("Bearer ")) {
                if(nexusConfig.getMonitoring().isEnabled()) {
                    meterRegistry.counter(
                            "nexusgate.auth.failures" ,
                            "reason" ,"missing_or_invalid_header"
                            ).increment();
                }
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String jwtToken = authHeader.substring(7);
            try {


                Claims claims = tokenVerifier.verify(jwtToken);
                // Resolve the configured identity claim
                String identityClaim = config.getIdentityClaim();

                Object identity = claims.get(identityClaim);
                if (identity == null) {
                    if (nexusConfig.getMonitoring().isEnabled()) {
                        meterRegistry.counter(
                                "nexusgate.auth.failures",
                                "reason" , "identity_missing"
                        ).increment();
                    }
                    exchange.getResponse()
                            .setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
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
                if (nexusConfig.getMonitoring().isEnabled()) {
                    meterRegistry.counter(
                            "nexusgate.auth.failures",
                            "reason" , "invalid_token"
                    ).increment();
                }
                exchange.getResponse()
                        .setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
        //default order is -100, so we set it to -90 to ensure it runs after the default filters
        return new OrderedGatewayFilter(filter, -90);
    }

    @Getter
    @Setter
    public static class Config {
        private List<String> publicEndpoints;
        private String servicePath;
        private String identityClaim;
    }
}
