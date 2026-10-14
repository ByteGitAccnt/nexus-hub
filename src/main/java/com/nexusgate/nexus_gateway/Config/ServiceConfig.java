package com.nexusgate.nexus_gateway.Config;

import com.nexusgate.nexus_gateway.Config.management.ManagementServiceConfig;
import lombok.Data;

import java.util.List;

@Data
public class ServiceConfig {

    private String url;
    private String path;
    private List<String>  publicEndpoints;
    private ManagementServiceConfig management;

}