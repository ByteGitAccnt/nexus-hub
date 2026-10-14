package com.nexusgate.nexus_gateway.Redis;

import lombok.Data;

@Data
public class RedisConfig {

    private String host;
    private int port;
    private String password;
}