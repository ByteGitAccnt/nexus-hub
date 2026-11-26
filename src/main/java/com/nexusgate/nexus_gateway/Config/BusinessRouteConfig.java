package com.nexusgate.nexus_gateway.Config;

import lombok.Data;

@Data
public class BusinessRouteConfig {

    private boolean enabled;
    private String path;
    private int order;
}
