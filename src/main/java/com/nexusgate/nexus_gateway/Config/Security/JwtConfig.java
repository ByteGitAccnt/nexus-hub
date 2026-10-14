package com.nexusgate.nexus_gateway.Config.Security;

import lombok.Data;

@Data
public class JwtConfig {

    private boolean enabled = false;
    private String algorithm;
    private String identityClaim;
    private JwtVerificationConfig verification;
    private String issuer;
    private String audience;

}
