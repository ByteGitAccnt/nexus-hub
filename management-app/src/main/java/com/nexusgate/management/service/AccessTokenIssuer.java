package com.nexusgate.management.service;

import com.nexusgate.management.config.JwtProperties;
import com.nexusgate.management.model.User;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Component
public class AccessTokenIssuer {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public AccessTokenIssuer(JwtProperties properties, SecretKey signingKey) {
        this.properties = properties;
        this.signingKey = signingKey;
    }

    public IssuedAccessToken issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        String token = Jwts.builder()
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("userid", user.getId().toString())
                .claim("organizationid", user.getOrganization().getId().toString())
                .claim("role", user.getRole().name())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
