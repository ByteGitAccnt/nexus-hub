package com.nexusgate.nexus_gateway.Config.Security;

import lombok.Data;

@Data
public class SecurityConfig {
    private boolean enabled = false;
    private JwtConfig jwt;
}
