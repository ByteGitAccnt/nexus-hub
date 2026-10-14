package com.nexusgate.nexus_gateway.Filter;

import lombok.Getter;
import lombok.Setter;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ManagementEndpointGatewayFilterFactory extends AbstractGatewayFilterFactory<ManagementEndpointGatewayFilterFactory.Config> {
//"<ManagementEndpointGatewayFilterFactory.Config> means : This filter factory has a configuration object of type Config."
    // every filter has a some sort of config for doing something , here it was used for data(endpoints) holding,some req/min etc.
    //A filter's configuration should contain the data required to configure that filter's behavior, not the entire application configuration.
    public ManagementEndpointGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {

        return (exchange, chain) -> {
            //fetch the incoming requests path
            String path = exchange.getRequest()
                    .getURI()
                    .getPath();

            // Extract the final endpoint from the request path
            String endpoint = path.substring(path.lastIndexOf("/") + 1);

            // Check whether the endpoint is configured, from the config we get the endpoints that was configured from the RouteBuilder
            //if it not present we shoot not found and else allow the rerouting by path rewriting managment/auth/health -> actuators/health
            if (!config.getEndpoints().contains(endpoint)) {

                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    @Getter
    @Setter
    public static class Config {
        // the config class is very important , it holds the endpoints we need validate which we will receive from the RouteBuilder
        private List<String> endpoints;
    }
}