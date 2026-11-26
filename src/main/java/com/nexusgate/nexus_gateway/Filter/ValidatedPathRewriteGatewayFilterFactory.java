package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.GatewayExchangeAttributes;
import com.nexusgate.nexus_gateway.Dto.ValidServiceDetails;
import com.nexusgate.nexus_gateway.Dto.ValidationResult;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ValidatedPathRewriteGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    public ValidatedPathRewriteGatewayFilterFactory() {
        super(Object.class);
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            ValidationResult validationResult = exchange
                    .getAttribute(GatewayExchangeAttributes.API_KEY_VALIDATION_RESULT);
            if (!hasTrustedIdentity(exchange, validationResult)) {
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }

            ValidServiceDetails service = validationResult.service();
            String basePath = normalizeBasePath(service.serviceBasePath());
            String targetPath = normalizeTargetPath(service.serviceTargetPath());
            String requestPath = exchange.getRequest().getPath().value();
            if (basePath == null || targetPath == null || requestPath == null) {
                return reject(exchange, HttpStatus.FORBIDDEN);
            }

            String suffix = matchingSuffix(requestPath, basePath);
            if (suffix == null) {
                return reject(exchange, HttpStatus.FORBIDDEN);
            }

            String rewrittenPath = joinPath(targetPath, suffix);
            return chain.filter(exchange.mutate()
                    .request(exchange.getRequest().mutate().path(rewrittenPath).build())
                    .build());
        };
    }

    private static boolean hasTrustedIdentity(ServerWebExchange exchange, ValidationResult result) {
        if (!"API_KEY".equals(exchange.getAttribute(GatewayExchangeAttributes.AUTH_MODE))
                || result == null
                || !result.valid()
                || result.organizationId() == null
                || result.service() == null
                || result.service().serviceId() == null) {
            return false;
        }

        String organizationId = exchange.getAttribute(GatewayExchangeAttributes.ORGANIZATION_ID);
        String serviceId = exchange.getAttribute(GatewayExchangeAttributes.SERVICE_ID);
        return result.organizationId().toString().equals(organizationId)
                && result.service().serviceId().toString().equals(serviceId);
    }

    private static String normalizeBasePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            return null;
        }
        return normalizeAbsolutePath(path);
    }

    private static String normalizeTargetPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            return null;
        }
        return normalizeAbsolutePath(path);
    }

    private static String normalizeAbsolutePath(String path) {
        String normalized = path.trim();
        if (normalized.isEmpty() || normalized.startsWith("//")) {
            return null;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return trimTrailingSlashes(normalized);
    }

    private static String trimTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static String matchingSuffix(String requestPath, String basePath) {
        if (basePath.equals("/")) {
            return requestPath.startsWith("/") ? requestPath.substring(1) : null;
        }
        if (requestPath.equals(basePath)) {
            return "";
        }
        if (requestPath.startsWith(basePath + "/")) {
            return requestPath.substring(basePath.length() + 1);
        }
        return null;
    }

    private static String joinPath(String targetPath, String suffix) {
        if (targetPath.isEmpty()) {
            return suffix.isEmpty() ? "/" : "/" + suffix;
        }
        return suffix.isEmpty() ? targetPath : targetPath + "/" + suffix;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
