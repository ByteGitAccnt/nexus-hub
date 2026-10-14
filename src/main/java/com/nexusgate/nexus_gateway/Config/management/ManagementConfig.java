package com.nexusgate.nexus_gateway.Config.management;

import lombok.Data;

import java.util.List;

@Data
public class ManagementConfig {

    private boolean enabled = false;
    private String basePath;
    private String targetPath;
    private List<String> endpoints;
}