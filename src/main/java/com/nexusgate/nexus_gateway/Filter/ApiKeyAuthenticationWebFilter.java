package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.GatewayPathMatcher;
import com.nexusgate.nexus_gateway.Config.GatewayExchangeAttributes;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Dto.ValidationRequest;
import com.nexusgate.nexus_gateway.Dto.ValidationResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "security.enabled", havingValue = "true")
public class ApiKeyAuthenticationWebFilter implements WebFilter, Ordered {

    private static final String VALIDATION_PATH = "api/v1/api-keys/internal/validate";
    private static final int ORDER = -90;

    private final NexusConfig nexusConfig;
    private final WebClient webClient;
    private final String internalSecret;
    private final Duration validationTimeout;

    public ApiKeyAuthenticationWebFilter(
            NexusConfig nexusConfig,
            WebClient webClient,
            @Value("${nexushub.internal.shared-secret}") String internalSecret,
            @Value("${nexushub.internal.validation-timeout:PT2S}") Duration validationTimeout) {
        this.nexusConfig = nexusConfig;
        this.webClient = webClient;
        this.internalSecret = internalSecret;
        this.validationTimeout = validationTimeout;
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("Internal API-key validation secret must be configured");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestPath = exchange.getRequest().getPath().value();
        var managementService = nexusConfig.getServices() == null
                ? null
                : nexusConfig.getServices().get("nexus-hub");
        String nexusHubPath = managementService == null ? null : managementService.getPath();
        if (GatewayPathMatcher.matchesPathOrDescendant(requestPath, nexusHubPath)) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        URI validationUri;
        try {
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
    }

    private Mono<Void> authorize(
            ValidationResult result,
            ServerWebExchange exchange,
            WebFilterChain chain) {
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
        exchange.getAttributes().put(GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT, result);
        exchange.getAttributes().put(GatewayExchangeAttributes.AUTH_MODE, "API_KEY");
        exchange.getAttributes().put(GatewayExchangeAttributes.ORGANIZATION_ID, organizationId);
        exchange.getAttributes().put(GatewayExchangeAttributes.SERVICE_ID, serviceId);

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

    @Override
    public int getOrder() {
        return ORDER;
    }
}
