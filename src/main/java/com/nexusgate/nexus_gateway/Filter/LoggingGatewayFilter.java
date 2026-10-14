package com.nexusgate.nexus_gateway.Filter;

import com.nexusgate.nexus_gateway.Config.Logging.LoggingFields;
import com.nexusgate.nexus_gateway.Config.NexusConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class LoggingGatewayFilter implements GlobalFilter, Ordered {
    private final NexusConfig nexusConfig;
    private final MeterRegistry metricRegistry;
    public LoggingGatewayFilter(NexusConfig nexusConfig, MeterRegistry metricRegistry) {
        this.nexusConfig = nexusConfig;
        this.metricRegistry = metricRegistry;
    }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!nexusConfig.getLogging().isEnabled()) {
            return chain.filter(exchange);
        }
        LoggingFields fields = nexusConfig.getLogging().getFields();
        Route route = exchange.getAttribute(
                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
        );
        String serviceName = route != null ? route.getId() : "unknown";
        // for duration calculation
        long startTime = fields.isDuration() ? System.currentTimeMillis() : 0;
        //setting request id if not present , accept if present
        String incomingRequestId = exchange.getRequest()
                .getHeaders()
                .getFirst("X-Request-ID");

        String requestId = (incomingRequestId == null || incomingRequestId.isBlank())
                ? UUID.randomUUID().toString()
                : incomingRequestId;

        exchange.getAttributes()
                .put("requestId", requestId);
        // adding the request id in response header
        exchange.getResponse()
                .getHeaders()
                .set("X-Request-ID", requestId);

        ServerWebExchange mutatedExchange =  exchange.mutate()
                .request(request -> request.headers(
                        httpHeaders -> httpHeaders.set("X-Request-ID", requestId)
                ))
                .build();
        // the doFinally will execute after the request terminates ON_COMPLETE, ON_ERROR and CANCEL
        return chain.filter(mutatedExchange)
                .doFinally(signal -> {
                    // after
                    Instant timestamp = null;
                    if (fields.isTimestamp()) {
                        timestamp = Instant.now();
                    }
                    long duration = 0;
                    if (fields.isDuration()) {
                        duration = System.currentTimeMillis() - startTime;
                    }
                    String method = null;
                    if (fields.isMethod()) {
                        method = String.valueOf(exchange
                                .getRequest()
                                .getMethod()
                        );
                    }
                    String path = null;
                    if (fields.isPath()) {
                        path = exchange.getRequest()
                                .getURI()
                                .getPath();
                    }
                    String clientIp = null;
                    if (fields.isClientIp()) {
                       clientIp = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                               .map(InetSocketAddress::getAddress)
                               .map(InetAddress::getHostAddress)
                               .orElse("unknown");
                    }
                    //Build the log message using only the * fields enabled in configuration.
                    StringBuilder logMessage = new StringBuilder("REQUEST");
                    if (fields.isTimestamp()) {
                        logMessage.append(" timestamp:")
                                .append(timestamp);
                    } if (fields.isMethod()) {
                        logMessage.append(" method:")
                                .append(method);
                    } if (fields.isPath()) {
                        logMessage.append(" path:")
                                .append(path);
                    } if (fields.isService()) {
                        logMessage.append(" service:")
                                .append(serviceName);
                    } if (fields.isStatus()) {
                        logMessage.append(" status:")
                                .append(exchange.getResponse()
                                        .getStatusCode());
                    } if (fields.isDuration()) {
                        logMessage.append(" duration:")
                                .append(duration)
                                .append("ms");
                    } if (fields.isRequestId())
                    { logMessage.append(" requestId:")
                            .append(requestId); }
                    if (fields.isClientIp()) {
                        logMessage.append(" clientIp:")
                                .append(clientIp);
                    }
                    log.info(logMessage.toString());
                    if(nexusConfig.getMonitoring().isEnabled()){
                        String outcome = getOutcome(exchange.getResponse().getStatusCode());
                        metricRegistry.counter(
                                "nexusgate.requests.routed",
                                "service", serviceName,
                                "outcome", outcome
                        ).increment();
                    }

                });
    }

    @Override
    public int getOrder() {
        return -200;
    }

    private String getOutcome(HttpStatusCode status) {
        if (status == null) {
            return "UNKNOWN";
        }

        int value = status.value();

        if (value >= 200 && value < 300) {
            return "SUCCESS";
        }
        if (value >= 300 && value < 400) {
            return "REDIRECTION";
        }
        if (value >= 400 && value < 500) {
            return "CLIENT_ERROR";
        }
        if (value >= 500 && value < 600) {
            return "SERVER_ERROR";
        }

        return "UNKNOWN";
    }
}