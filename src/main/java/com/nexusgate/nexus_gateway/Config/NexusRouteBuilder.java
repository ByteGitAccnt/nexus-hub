package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.management.ManagementConfig;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class NexusRouteBuilder implements RouteDefinitionLocator {

    private final NexusConfig config;

    public NexusRouteBuilder(NexusConfig config) {
        this.config = config;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {

        List<RouteDefinition> routes = new ArrayList<>();
        for (Map.Entry<String, ServiceConfig> entry : config.getServices().entrySet()) {

            String serviceName = entry.getKey();
            ServiceConfig service = entry.getValue();
            ServiceConfig nexusHubService = config.getServices().get("nexus-hub");
            if (config.getSecurity().isEnabled()
                    && (nexusHubService == null
                    || nexusHubService.getPath() == null
                    || nexusHubService.getPath().isBlank()
                    || nexusHubService.getUrl() == null
                    || nexusHubService.getUrl().isBlank())) {
                throw new IllegalStateException(
                        "The nexus-hub service path and URL must be configured when gateway security is enabled");
            }
            String nexusHubPath = nexusHubService == null ? null : nexusHubService.getPath();
            List<String> publicEndpoints = nexusHubService == null
                    ? List.of()
                    : nexusHubService.getPublicEndpoints();

            FilterDefinition jwtFilter = null;
            if (config.getSecurity().isEnabled()) {
                jwtFilter = new FilterDefinition();
                jwtFilter.setName("JwtAuthentication");
                Map<String, String> args = new HashMap<>();
                if (publicEndpoints != null && !publicEndpoints.isEmpty()) {
                    args.put("publicEndpoints", String.join(",", publicEndpoints));
                }
                args.put("nexusHubPath", nexusHubPath);
                String identityClaim = config.getSecurity()
                        .getJwt()
                        .getIdentityClaim();
                if (identityClaim == null || identityClaim.isBlank()) {
                    identityClaim = "sub";
                }
                args.put("identityClaim", identityClaim);

                jwtFilter.setArgs(args);
            }
            FilterDefinition apiKeyFilter = null;
            if (config.getSecurity().isEnabled()) {
                apiKeyFilter = new FilterDefinition();
                apiKeyFilter.setName("ApiKeyAuthentication");
                Map<String, String> args = new HashMap<>();
                args.put("nexusHubPath", nexusHubPath);
                apiKeyFilter.setArgs(args);
            }
            // inner rate limiter filter
            FilterDefinition innerLimiter = null;
            if (config.getRateLimit().isEnabled()) {
                innerLimiter = new FilterDefinition();
                innerLimiter.setName("InnerRateLimit");
            }

            // Business API route
            RouteDefinition apiRoute = new RouteDefinition();

            apiRoute.setId(serviceName);
            apiRoute.setUri(URI.create(service.getUrl()));

            PredicateDefinition apiPredicate = new PredicateDefinition();
            apiPredicate.setName("Path");
            apiPredicate.addArg("pattern", service.getPath());

            apiRoute.setPredicates(List.of(apiPredicate));
            // JWT authentication and inner rate limiter filter adding to the route
            List<FilterDefinition> filters = new ArrayList<>();

            if (jwtFilter != null) {
                filters.add(jwtFilter);
            }

            if (apiKeyFilter != null) {
                filters.add(apiKeyFilter);
            }

            if (innerLimiter != null) {
                filters.add(innerLimiter);
            }
            if (service.getPath().equals("/nexushub/**")) {
                FilterDefinition stripPrefix = new FilterDefinition();
                stripPrefix.setName("StripPrefix");
                stripPrefix.addArg("parts", "1");
                filters.add(stripPrefix);
            }
            apiRoute.setFilters(filters);
            routes.add(apiRoute);
            // Management route
            if (config.getManagement() != null && config.getManagement().isEnabled()) {

                List<String> endpoints = resolveManagementEndpoints(config, service);

                if (!endpoints.isEmpty()) {

                    RouteDefinition managementRoute = createManagementRoute(
                            config,
                            serviceName,
                            service,
                            endpoints);

                    routes.add(managementRoute);
                }
            }
        }

        return Flux.fromIterable(routes);
    }

    private List<String> resolveManagementEndpoints(NexusConfig config, ServiceConfig service) {

        if (service.getManagement() != null && service.getManagement().getEndpoints() != null) {
            return service.getManagement().getEndpoints();
        }

        return config.getManagement().getEndpoints();
    }

    private RouteDefinition createManagementRoute(
            NexusConfig config,
            String serviceName,
            ServiceConfig service,
            List<String> endpoints) {

        ManagementConfig management = config.getManagement();

        RouteDefinition route = new RouteDefinition();

        route.setId(serviceName + "-management");

        route.setUri(URI.create(service.getUrl()));

        // -----------------------------------------
        // Predicate
        // /management/auth/**
        // -----------------------------------------

        PredicateDefinition predicate = new PredicateDefinition();

        predicate.setName("Path");

        predicate.addArg(
                "pattern",
                management.getBasePath()
                        + "/"
                        + serviceName
                        + "/**");
        route.setPredicates(List.of(predicate));
        // -----------------------------------------
        // Custom management endpoint filter
        // -----------------------------------------
        FilterDefinition managementFilter = new FilterDefinition();
        managementFilter.setName("ManagementEndpoint");
        managementFilter.addArg("endpoints", String.join(",", endpoints));
        // -----------------------------------------
        // RewritePath
        //
        // /management/auth/health
        // ↓
        // /actuator/health
        // -----------------------------------------

        FilterDefinition rewrite = new FilterDefinition();
        rewrite.setName("RewritePath");
        rewrite.addArg(
                "regexp",
                management.getBasePath()
                        + "/"
                        + serviceName
                        + "/(?<endpoint>.*)");

        rewrite.addArg(
                "replacement",
                management.getTargetPath()
                        + "/${endpoint}");

        // Order matters:
        //
        // 1. Check whether endpoint is allowed
        // 2. Rewrite the path
        //
        route.setFilters(List.of(managementFilter, rewrite));

        return route;
    }
}