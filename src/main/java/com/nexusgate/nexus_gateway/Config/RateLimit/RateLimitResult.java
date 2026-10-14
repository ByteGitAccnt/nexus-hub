package com.nexusgate.nexus_gateway.Config.RateLimit;

public record RateLimitResult(
        boolean allowed,
        long remaining,
        long retryAfter
) {
}
