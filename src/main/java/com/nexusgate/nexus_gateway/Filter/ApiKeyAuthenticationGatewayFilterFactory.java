package com.nexusgate.nexus_gateway.Filter;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true")
public class ApiKeyAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiKeyAuthenticationGatewayFilterFactory.Config> {

    public ApiKeyAuthenticationGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter = (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // The preceding JWT filter has already validated and authenticated this token.
                return chain.filter(exchange);
            }

            String requestPath = exchange.getRequest().getPath().value();
            String servicePath = config.getServicePath() == null ? "" : config.getServicePath();
            String relativePath = requestPath.startsWith(servicePath)
                    ? requestPath.substring(servicePath.length())
                    : requestPath;
            boolean isPublic = config.getPublicEndpoints() != null
                    && config.getPublicEndpoints().stream().anyMatch(relativePath::equals);
            if (isPublic && authHeader == null) {
                return chain.filter(exchange);
            }

            if (exchange.getRequest().getHeaders().getFirst("X-API-Key") != null) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            return chain.filter(exchange);
        };
        return new OrderedGatewayFilter(filter, -85);
    }

    @Getter
    @Setter
    public static class Config {
        private List<String> publicEndpoints;
        private String servicePath;
    }
}
