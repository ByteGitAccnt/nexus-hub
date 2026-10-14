package com.nexusgate.nexus_gateway.Config.Security;

import lombok.Data;

@Data
public class JwtVerificationConfig {

    private String type;
    private String secret;
    private String publicKey;
    private String uri;

}
