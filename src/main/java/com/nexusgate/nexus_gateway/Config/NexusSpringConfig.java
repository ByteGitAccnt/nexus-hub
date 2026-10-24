package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.Security.JwtConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

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
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
