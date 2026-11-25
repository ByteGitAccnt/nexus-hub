package com.nexusgate.nexus_gateway.Config;

import java.util.List;

public final class GatewayPathMatcher {

    private GatewayPathMatcher() {
    }

    public static String pathPrefix(String configuredPattern) {
        if (configuredPattern == null || configuredPattern.isBlank()) {
            return "";
        }
        String prefix = configuredPattern.trim();
        if (prefix.endsWith("/**")) {
            prefix = prefix.substring(0, prefix.length() - 3);
        } else if (prefix.endsWith("/*")) {
            prefix = prefix.substring(0, prefix.length() - 2);
        }
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    public static boolean matchesPathOrDescendant(String requestPath, String configuredPattern) {
        String prefix = pathPrefix(configuredPattern);
        if (prefix.isEmpty() || requestPath == null) {
            return false;
        }
        return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
    }

    public static boolean matchesPublicEndpoint(
            String requestPath,
            String servicePattern,
            List<String> publicEndpoints
    ) {
        if (publicEndpoints == null || !matchesPathOrDescendant(requestPath, servicePattern)) {
            return false;
        }
        String prefix = pathPrefix(servicePattern);
        String relativePath = requestPath.substring(prefix.length());
        return publicEndpoints.stream().anyMatch(relativePath::equals);
    }
}
