package com.nexusgate.nexus_gateway.Config.RateLimit;


import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

@Component
public class ClientIpResolver {

    public String resolve(ServerWebExchange exchange) {

        InetSocketAddress remoteAddress =
                exchange.getRequest().getRemoteAddress();

        if (remoteAddress == null) {
            throw new IllegalStateException(
                    "Unable to determine client IP address"
            );
        }

        return remoteAddress.getAddress().getHostAddress();
    }
}