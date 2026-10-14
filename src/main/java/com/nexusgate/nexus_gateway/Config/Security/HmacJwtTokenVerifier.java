package com.nexusgate.nexus_gateway.Config.Security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(
        name = "security.enabled",
        havingValue = "true"
)
public class HmacJwtTokenVerifier implements JwtTokenVerifier {

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;

    public HmacJwtTokenVerifier(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;

        String secret = jwtConfig.getVerification().getSecret();
        this.secretKey = createSecretKey(
                secret,
                jwtConfig.getAlgorithm()
        );
    }

    @Override
    public Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(jwtConfig.getIssuer())
                    .requireAudience(jwtConfig.getAudience())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        } catch (JwtException | IllegalArgumentException e) {
            throw e;
        }
    }

    private SecretKey createSecretKey(String secret, String algorithm) {

        String keyAlgorithm = switch (algorithm) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalArgumentException(
                    "Unsupported HMAC algorithm: " + algorithm
            );
        };

        return new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                keyAlgorithm
        );
    }
}
