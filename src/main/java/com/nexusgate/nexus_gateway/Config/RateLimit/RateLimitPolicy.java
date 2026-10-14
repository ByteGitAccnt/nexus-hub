package com.nexusgate.nexus_gateway.Config.RateLimit;

import lombok.Data;

@Data
public class RateLimitPolicy {

    private boolean enabled = false;
    private String algorithm;
    private long capacity;
    private long refillRate;
    private long refillPeriodSeconds;
}
