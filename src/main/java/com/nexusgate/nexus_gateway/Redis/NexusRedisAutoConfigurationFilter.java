package com.nexusgate.nexus_gateway.Redis;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

public class NexusRedisAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {

        boolean redisEnabled =
                environment.getProperty(
                        "rateLimit.enabled",
                        Boolean.class,
                        false
                );

        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {

            String autoConfiguration = autoConfigurationClasses[i];
            if (!redisEnabled &&
                    (
                            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
                                    .equals(autoConfiguration)
                                    ||
                                    "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration"
                                            .equals(autoConfiguration)
                    )) {

                matches[i] = false;
            } else {
                matches[i] = true;
            }
        }

        return matches;
    }
}
