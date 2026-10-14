package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.Logging.LoggingConfig;
import com.nexusgate.nexus_gateway.Config.Monitoring.MonitoringConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitConfig;
import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import com.nexusgate.nexus_gateway.Config.management.ManagementConfig;
import com.nexusgate.nexus_gateway.Config.Security.SecurityConfig;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class NexusConfigLoader {

    private final Path configPath = Path.of("nexus.yml");
    private final EnvironmentVariableResolver environmentResolver;

    public NexusConfigLoader(EnvironmentVariableResolver environmentResolver) {
        this.environmentResolver = environmentResolver;
    }

    public NexusConfig load() {
        try {
            String yamlContent = Files.readString(configPath);
            String resolvedYaml = environmentResolver.resolve(yamlContent);
            Yaml yaml = new Yaml();
            NexusConfig config = yaml.loadAs(resolvedYaml, NexusConfig.class);
            if (config == null) {
                throw new IllegalStateException(
                        "NexusGate configuration is empty: " + configPath
                );
            }
            if(config.getLogging() == null){
                config.setLogging(new LoggingConfig());
            }
            if(config.getManagement() == null){
                config.setManagement(new ManagementConfig());
            }
            if(config.getRateLimit() == null){
                config.setRateLimit(new RateLimitConfig());
            }
            if (config.getSecurity() == null) {
                config.setSecurity(new SecurityConfig());
            }

            if (config.getSecurity().getJwt() == null) {
                config.getSecurity().setJwt(new JwtConfig());
            }
            if(config.getMonitoring() == null){
                config.setMonitoring(new MonitoringConfig());
            }
            return config;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load NexusGate configuration: "
                            + configPath,
                    e
            );
        }
    }
}