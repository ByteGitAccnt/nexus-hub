package com.nexusgate.nexus_gateway.Config.RateLimit;

import lombok.Data;

@Data
public class RateLimitConfig {
    private boolean enabled = false;
    private String redisFailureStrategy;
    private long redisTimeoutMs;
    private RateLimitPolicy outer;
    private RateLimitPolicy inner;
}
