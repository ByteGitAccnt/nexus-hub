package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.GatewayPathMatcher;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Dto.ValidationRequest;
import com.nexusgate.nexus_gateway.Dto.ValidationResult;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true")
public class ApiKeyAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ApiKeyAuthenticationGatewayFilterFactory.Config> {

    private static final String VALIDATION_PATH = "api/v1/api-keys/internal/validate";

    private final NexusConfig nexusConfig;
    private final WebClient webClient;
    private final String internalSecret;
    private final Duration validationTimeout;

    @Autowired
    public ApiKeyAuthenticationGatewayFilterFactory(
            NexusConfig nexusConfig,
            WebClient webClient,
            @Value("${nexushub.internal.shared-secret}") String internalSecret,
            @Value("${nexushub.internal.validation-timeout:PT2S}") Duration validationTimeout) {
        this(nexusConfig, webClient, internalSecret, validationTimeout, true);
    }

    ApiKeyAuthenticationGatewayFilterFactory(
            NexusConfig nexusConfig,
            WebClient webClient,
            String internalSecret,
            Duration validationTimeout,
            boolean validateConfiguration) {
        super(Config.class);
        this.nexusConfig = nexusConfig;
        this.webClient = webClient;
        this.internalSecret = internalSecret;
        this.validationTimeout = validationTimeout;
        if (validateConfiguration && (internalSecret == null || internalSecret.isBlank())) {
            throw new IllegalStateException("Internal API-key validation secret must be configured");
        }
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter = (exchange, chain) -> {
            String requestPath = exchange.getRequest().getPath().value();
            if (GatewayPathMatcher.matchesPathOrDescendant(requestPath, config.getNexusHubPath())) {
                return chain.filter(exchange);
            }

            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey == null || apiKey.isBlank()) {
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }

            URI validationUri;
            try {
                var managementService = nexusConfig.getServices().get("nexus-hub");
                if (managementService == null || managementService.getUrl() == null
                        || managementService.getUrl().isBlank()) {
                    return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
                }
                String baseUrl = managementService.getUrl().endsWith("/")
                        ? managementService.getUrl()
                        : managementService.getUrl() + "/";
                validationUri = URI.create(baseUrl).resolve(VALIDATION_PATH);
            } catch (RuntimeException exception) {
                return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
            }

            return webClient.post()
                    .uri(validationUri)
                    .header("X-Internal-Secret", internalSecret)
                    .bodyValue(new ValidationRequest(apiKey, requestPath))
                    .retrieve()
                    .bodyToMono(ValidationResult.class)
                    .timeout(validationTimeout)
                    .flatMap(result -> authorize(result, exchange, chain))
                    .onErrorResume(WebClientResponseException.class,
                            exception -> reject(exchange, statusFor(exception)))
                    .onErrorResume(exception -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE));
        };
        return new OrderedGatewayFilter(filter, -85);
    }

    private Mono<Void> authorize(
            ValidationResult result,
            ServerWebExchange exchange,
            GatewayFilterChain chain) {
        if (result == null || !result.valid()
                || result.apiKeyId() == null
                || result.organizationId() == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        if (result.service() == null || result.service().serviceId() == null) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        String apiKeyId = result.apiKeyId().toString();
        String organizationId = result.organizationId().toString();
        String serviceId = result.service().serviceId().toString();
        exchange.getAttributes().put("authMode", "API_KEY");
        exchange.getAttributes().put("organizationId", organizationId);
        exchange.getAttributes().put("serviceId", serviceId);

        ServerHttpRequest authenticatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-API-Key");
                    headers.remove("X-Internal-Secret");
                    headers.remove("X-Principal");
                    headers.remove("X-Organization-ID");
                    headers.remove("X-Service-ID");
                })
                .header("X-Principal", apiKeyId)
                .header("X-Organization-ID", organizationId)
                .header("X-Service-ID", serviceId)
                .build();
        ServerWebExchange authenticatedExchange = exchange.mutate()
                .request(authenticatedRequest)
                .build();
        var authentication = new UsernamePasswordAuthenticationToken(
                "apikey:" + apiKeyId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
        log.atInfo().log("Api authentication was suucess full {} {} {}", apiKeyId, organizationId, serviceId);
        return chain.filter(authenticatedExchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private static HttpStatus statusFor(WebClientResponseException exception) {
        if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
            return HttpStatus.FORBIDDEN;
        }
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Getter
    @Setter
    public static class Config {
        private String nexusHubPath;
    }
}
