package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NexusSpringConfig {

    @Bean
    public NexusConfig nexusConfig(NexusConfigLoader loader) {
        // we're creating bean for NexusConfig and loading it using NexusConfigLoader
        return loader.load();
    }

    @Bean
    public JwtConfig jwtConfig(NexusConfig nexusConfig) {
        // for hmac we need a jwtConfig bean , here we create
        return nexusConfig
                .getSecurity()
                .getJwt();
    }
}
