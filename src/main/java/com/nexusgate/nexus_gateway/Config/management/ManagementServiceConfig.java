package com.nexusgate.nexus_gateway.Config.management;

import lombok.Data;

import java.util.List;

@Data
public class ManagementServiceConfig {

    private List<String> endpoints;
}