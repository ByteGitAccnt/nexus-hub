package com.nexusgate.nexus_gateway.Config.Security;

import io.jsonwebtoken.Claims;

public interface JwtTokenVerifier {

    Claims verify(String token);
}
