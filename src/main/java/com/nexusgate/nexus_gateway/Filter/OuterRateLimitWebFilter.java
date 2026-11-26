package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.*;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "rateLimit.enabled", havingValue = "true")
public class OuterRateLimitWebFilter implements WebFilter, Ordered {

    private final ClientIpResolver clientIpResolver;
    private final RateLimitKeyGenerator KeyGenerator;
    private final RedisTokenBucket tokenBucket;
    private final NexusConfig nexusConfig;
    private final MeterRegistry meterRegistry;

    public OuterRateLimitWebFilter(ClientIpResolver clientIpResolver, RateLimitKeyGenerator rateLimitKeyGenerator,
            RedisTokenBucket tokenBucket, NexusConfig nexusConfig, MeterRegistry meterRegistry) {
        this.clientIpResolver = clientIpResolver;
        this.KeyGenerator = rateLimitKeyGenerator;
        this.tokenBucket = tokenBucket;
        this.nexusConfig = nexusConfig;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        if (!nexusConfig.getRateLimit().isEnabled()) {
            return chain.filter(exchange);
        }

        RateLimitPolicy policy = nexusConfig.getRateLimit().getOuter();
        if (!policy.isEnabled()) {
            return chain.filter(exchange);
        }
        String ip = clientIpResolver.resolve(exchange);
        String bucketKey = KeyGenerator.forIp(ip);
        return tokenBucket.consume(bucketKey, policy)
                .flatMap(result -> handleResult(exchange, chain, result, policy))
                .onErrorResume(error -> handleRedisFailure(exchange, chain, error));
    }

    private Mono<Void> handleResult(ServerWebExchange exchange, WebFilterChain chain, RateLimitResult result,
            RateLimitPolicy policy) {
        exchange.getResponse()
                .getHeaders()
                .add("X-RateLimit-Limit", String.valueOf(policy.getCapacity()));
        exchange.getResponse()
                .getHeaders()
                .add("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        if (!result.allowed()) {
            if (nexusConfig.getMonitoring().isEnabled()) {
                meterRegistry.counter(
                        "nexusgate.rate_limit.rejections",
                        "limiter", "outer",
                        "reason", "limit_exceeded").increment();
            }
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse()
                    .getHeaders()
                    .add("Retry-After", String.valueOf(result.retryAfter()));
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private Mono<Void> handleRedisFailure(ServerWebExchange exchange, WebFilterChain chain, Throwable error) {
        String strategy = nexusConfig.getRateLimit().getRedisFailureStrategy();
        // if strategy is fail open , then we allow request to pass without causing an
        // error! we chose availability over security
        // if fail-closed then we chose security over availability and the request will
        // be rejected
        if ("fail-open".equalsIgnoreCase(strategy)) {
            return chain.filter(exchange);
        }
        if (nexusConfig.getMonitoring().isEnabled()) {
            meterRegistry.counter(
                    "nexusgate.rate_limit.rejections",
                    "limiter", "outer",
                    "reason", "redis_failure").increment();
        }
        exchange.getResponse()
                .setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -150;
    }
}
