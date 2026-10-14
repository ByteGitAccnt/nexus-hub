package com.nexusgate.nexus_gateway.Config.RateLimit;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Service
@ConditionalOnProperty(
        name = "rateLimit.enabled",
        havingValue = "true"
)
public class RedisTokenBucket {
    // a reactive template for interacting with Redis
    //we have normal redis template and reactive redis template, we are using reactive redis template
    // because it is non-blocking and asynchronous
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    // a script that will be executed in Redis to implement the token bucket algorithm
    private final DefaultRedisScript<List> script;
    private final NexusConfig nexusConfig;

    public RedisTokenBucket(ReactiveRedisTemplate<String, String> redisTemplate , NexusConfig nexusConfig) {
        this.redisTemplate = redisTemplate;

        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        // we set the result type of the script to be a list, because the script will return a list of values
        this.script.setResultType(List.class);
        this.nexusConfig = nexusConfig;
    }
    // the consume method takes a bucket key and a rate limit policy, and returns a Mono of RateLimitResult
    public Mono<RateLimitResult> consume(String bucketKey, RateLimitPolicy policy) {
        Duration timeout = Duration.ofMillis(nexusConfig.getRateLimit().getRedisTimeoutMs());
        //the excute method of the redis template takes the script, the keys, and the arguments, and returns a Mono of the result
        return redisTemplate.execute(
                        script,
                        List.of(bucketKey),
                        List.of(
                                String.valueOf(policy.getCapacity()),
                                String.valueOf(policy.getRefillRate()),
                                String.valueOf(policy.getRefillPeriodSeconds()),
                                String.valueOf(calculateTtl(policy))
                        )
                )
                .single()
                .timeout(timeout)
                .map(this::toResult);// convert the result to a RateLimitResult object
    }

    private RateLimitResult toResult(List<?> result) {

        long allowed = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long retryAfter = ((Number) result.get(2)).longValue();

        return new RateLimitResult(
                allowed == 1,// if allowed is 1, then the request is allowed, otherwise it is not
                remaining,
                retryAfter
        );
    }
    private long calculateTtl(RateLimitPolicy policy) {

        return (long) Math.ceil(
                (double) policy.getCapacity()
                        * policy.getRefillPeriodSeconds()
                        / policy.getRefillRate()
        );
    }
}