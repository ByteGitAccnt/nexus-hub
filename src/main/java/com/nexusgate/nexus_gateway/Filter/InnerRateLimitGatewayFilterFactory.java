package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.NexusConfig;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitKeyGenerator;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitPolicy;
import com.nexusgate.nexus_gateway.Config.RateLimit.RateLimitResult;
import com.nexusgate.nexus_gateway.Config.RateLimit.RedisTokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(
        name = "rateLimit.enabled",
        havingValue = "true"
)
public class InnerRateLimitGatewayFilterFactory  extends AbstractGatewayFilterFactory<Object> {

    private final RateLimitKeyGenerator keyGenerator;
    private final RedisTokenBucket tokenBucket;
    private final NexusConfig nexusConfig;
    private final MeterRegistry meterRegistry;

    public InnerRateLimitGatewayFilterFactory(RateLimitKeyGenerator keyGenerator, RedisTokenBucket tokenBucket,
                                              NexusConfig nexusConfig, MeterRegistry meterRegistry) {
        super(Object.class);
        this.keyGenerator = keyGenerator;
        this.tokenBucket = tokenBucket;
        this.nexusConfig = nexusConfig;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Object config){
        GatewayFilter filter =  (exchange , chain) -> {

            if(!nexusConfig.getRateLimit().isEnabled()){
                return chain.filter(exchange);
            }
            RateLimitPolicy policy = nexusConfig.getRateLimit().getInner();

            if (!policy.isEnabled()) {
                return chain.filter(exchange);
            }

            return ReactiveSecurityContextHolder.getContext()
                    .hasElement()
                    .flatMap(hasContext -> {

                        if (!hasContext) {// If there is no security context, treat the request as unauthenticated
                            return chain.filter(exchange);
                            //so no need rate limiting
                        }

                        return ReactiveSecurityContextHolder.getContext()
                                .map(context -> context.getAuthentication())
                                .filter(Authentication::isAuthenticated)
                                .flatMap(authentication ->
                                        handleAuthenticatedRequest(
                                                authentication,
                                                exchange,
                                                chain,
                                                policy
                                        )
                                ) ;
                    });
        };
        // determine the order of the filter, -80 is chosen to ensure it runs before most other filters
        return new OrderedGatewayFilter(filter, -80);
    }

    private Mono<Void> handleAuthenticatedRequest(Authentication authentication , ServerWebExchange exchange, GatewayFilterChain chain, RateLimitPolicy policy) {
        String userId = authentication.getName();
        String bucketKey = keyGenerator.forUser(userId);
        return tokenBucket.consume(bucketKey , policy)
                .flatMap(result -> handleResult(exchange , chain , policy, result))
                .onErrorResume(error ->
                    handleRedisFailure(
                        exchange,
                        chain
                    )
        );

    }
    private Mono<Void> handleResult(ServerWebExchange exchange , GatewayFilterChain chain, RateLimitPolicy policy, RateLimitResult result) {
        exchange.getResponse()
                .getHeaders()
                .set("X-RateLimit-Limit", String.valueOf(policy.getCapacity()));
        exchange.getResponse()
                .getHeaders()
                .set("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if(!result.allowed()){
            if(nexusConfig.getMonitoring().isEnabled()) {
                meterRegistry.counter(
                        "nexusgate.rate_limit.rejections",
                        "limiter", "inner",
                        "reason", "limit_exceeded"
                ).increment();
            }
            exchange.getResponse()
                    .getHeaders()
                    .set("Retry-After", String.valueOf(result.retryAfter()));

            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
    private Mono<Void> handleRedisFailure(ServerWebExchange exchange, GatewayFilterChain chain) {
        String strategy = nexusConfig.getRateLimit().getRedisFailureStrategy();
        //if strategy is fail open , then we allow request to pass without causing an error! we chose availability over security
        //if fail-closed then we chose security over availability and the request will be rejected
        if("fail-open".equalsIgnoreCase(strategy)){
            return chain.filter(exchange);
        }
        if(nexusConfig.getMonitoring().isEnabled()) {
            meterRegistry.counter(
                    "nexusgate.rate_limit.rejections",
                    "limiter", "inner",
                    "reason", "redis_failure"
            ).increment();
        }
        exchange.getResponse()
                .setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }
}
