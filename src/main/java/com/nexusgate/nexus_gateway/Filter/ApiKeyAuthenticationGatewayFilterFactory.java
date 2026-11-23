package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Dto.ValidationRequest;
import com.nexusgate.nexus_gateway.Dto.ValidationResult;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true")
public class ApiKeyAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiKeyAuthenticationGatewayFilterFactory.Config> {
    private final NexusConfig nexusConfig;
    private final int apiLookupPrefixLength;
    private final WebClient webClient;
    private final String INTERNAL_SECRET;

    public ApiKeyAuthenticationGatewayFilterFactory(NexusConfig nexusConfig,
            @Value("${API_LOOKUP_PREFIX_LENGTH}") int apiLookupPrefixLength, WebClient webClient,
            @Value("${NEXUSHUB_INTERNAL_SHARED_SECRET}") String internal_secret) {
        super(Config.class);
        this.nexusConfig = nexusConfig;
        this.apiLookupPrefixLength = apiLookupPrefixLength;
        this.webClient = webClient;
        this.INTERNAL_SECRET = internal_secret;
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter = (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
            // if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // // The preceding JWT filter has already validated and authenticated this
            // token.
            // return chain.filter(exchange);
            // }

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
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey == null || apiKey.isBlank() || apiKey.length() < apiLookupPrefixLength) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            String validationUrl = nexusConfig.getServices().get("nexus-hub").getUrl()
                    + "/api/v1/api-keys/internal/validate";
            return webClient.post()
                    .uri(validationUrl)
                    .header("X-Internal-Secret", INTERNAL_SECRET)
                    .bodyValue(new ValidationRequest(
                            apiKey,
                            apiKey.substring(0, apiLookupPrefixLength),
                            exchange.getRequest().getPath().value()))
                    .retrieve()
                    .bodyToMono(ValidationResult.class)
                    .flatMap(result -> {
                        if (!result.valid()) {
                            exchange.getResponse()
                                    .setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }
                        if (result.service() == null) {
                            exchange.getResponse()
                                    .setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }
                        String rewrittenPath = rewritePath(
                                exchange.getRequest().getPath().value(),
                                result.service().serviceBasePath(),
                                result.service().serviceTargetPath());
                        exchange.getAttributes().put(
                                "validatedOrganizationId",
                                result.organizationId());

                        exchange.getAttributes().put(
                                "validatedService",
                                result.service());
                        // Rewrite the incoming request path.
                        ServerHttpRequest mutatedRequest = exchange.getRequest()
                                .mutate()
                                .path(rewrittenPath)
                                .build();
                        ServerWebExchange mutatedExchange = exchange.mutate()
                                .request(mutatedRequest)
                                .build();
                        return chain.filter(mutatedExchange);
                    })
                    .onErrorResume(error -> {
                        exchange.getResponse()
                                .setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    });
        };
        return new OrderedGatewayFilter(filter, -85);
    }

    private String rewritePath(String requestPath, String basePath, String targetPath) {
        String path = normalizePath(requestPath);
        String base = normalizePath(basePath);
        String target = normalizePath(targetPath);

        if (!path.equals(base) && !path.startsWith(base + "/")) {
            throw new IllegalArgumentException(
                    "Request path does not match service base path");
        }

        String suffix = path.substring(base.length());

        return target + suffix;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    @Getter
    @Setter
    public static class Config {
        private List<String> publicEndpoints;
        private String servicePath;
    }
}
