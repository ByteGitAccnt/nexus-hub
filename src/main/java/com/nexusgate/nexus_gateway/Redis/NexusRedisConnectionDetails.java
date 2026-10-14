package com.nexusgate.nexus_gateway.Redis;


import com.nexusgate.nexus_gateway.Config.NexusConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "rateLimit.enabled",
        havingValue = "true"
)
public class NexusRedisConnectionDetails implements DataRedisConnectionDetails.Standalone {
// we are overriding the redis's data connection details to push our host and other port details
    // we don't need an explicit bean for ReactiveRedisTemplate as spring will automatically create it for us and consume the custom details we are providing.
    private final NexusConfig nexusConfig;

    public NexusRedisConnectionDetails(NexusConfig nexusConfig) {
        // we import the custom details from the nexus config and push it to the redis connection details
        this.nexusConfig = nexusConfig;
    }

    @Override
    public String getHost() {
        return nexusConfig.getRedis().getHost();
    }

    @Override
    public int getPort() {
        return nexusConfig.getRedis().getPort();
    }
}
