package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.Logging.LoggingConfig;
import com.nexusgate.nexus_gateway.Config.Monitoring.MonitoringConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import com.nexusgate.nexus_gateway.Config.management.ManagementConfig;
import com.nexusgate.nexus_gateway.Redis.RedisConfig;
import lombok.Data;

import java.util.Map;

@Data
public class NexusConfig {

    private ManagementConfig management;
    private Map<String, ServiceConfig> services;
    private SecurityConfig security;
    private RedisConfig redis;
    private RateLimitConfig rateLimit;
    private LoggingConfig logging;
    private MonitoringConfig monitoring;
}