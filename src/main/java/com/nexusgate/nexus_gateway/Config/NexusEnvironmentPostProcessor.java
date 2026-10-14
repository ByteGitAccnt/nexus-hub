package com.nexusgate.nexus_gateway.Config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/*
 A EnvPostProcessor is an interface in spring that will run before the application context starts
Spring Boot calls it very early during startup, before beans are created.
it's for adding new context from external to the spring environment[Environment contains configuration values such as
application.properties , application.yml,Environment variables,Command-line arguments,External config sources]
we are going to inject our nexus.ymls config to spring
*/

public class NexusEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Path configPath = Path.of("nexus.yml");
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try{
            String yamlContent = Files.readString(configPath);
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(yamlContent);

            Map<String, Object> properties = new HashMap<>();

            Object rateLimitObject = yamlData.get("rateLimit");

            if (rateLimitObject instanceof Map<?, ?> rateLimit) {
                Object enabled = rateLimit.get("enabled");
                if (enabled != null) {
                    properties.put(
                            "rateLimit.enabled",
                            String.valueOf(enabled)
                    );
                }
            }
            Object securityObject = yamlData.get("security");

            if (securityObject instanceof Map<?, ?> security) {

                Object enabled = security.get("enabled");
                if (enabled != null) {
                    properties.put(
                            "security.enabled",
                            String.valueOf(enabled)
                    );
                }
            }

//            environment.getPropertySources().addFirst(
//                    new MapPropertySource(
//                            "nexusRateLimit",
//                            properties
//                    )
//            );
            if (!properties.isEmpty()) {
                environment.getPropertySources().addFirst(
                        new MapPropertySource(
                                "nexusConfig",
                                properties
                        )
                );
            }

        }catch (IOException e){
            throw new IllegalStateException(
                    "Failed to load NexusGate configuration: "
                            + configPath,
                    e
            );
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

}
