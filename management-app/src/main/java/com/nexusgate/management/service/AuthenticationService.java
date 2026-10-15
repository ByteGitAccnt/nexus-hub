package com.nexusgate.management.service;

import com.nexusgate.management.config.JwtProperties;
import com.nexusgate.management.dto.LoginRequest;
import com.nexusgate.management.dto.LoginResponse;
import com.nexusgate.management.exception.InvalidCredentialsException;
import com.nexusgate.management.exception.InvalidRefreshTokenException;
import com.nexusgate.management.model.Organization;
import com.nexusgate.management.model.OrganizationStatus;
import com.nexusgate.management.model.RefreshToken;
import com.nexusgate.management.model.User;
import com.nexusgate.management.model.UserStatus;
import com.nexusgate.management.repository.RefreshTokenRepository;
import com.nexusgate.management.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final JwtProperties jwtProperties;

    public AuthenticationService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer,
            JwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(InvalidCredentialsException::new);
        Organization organization = user.getOrganization();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())
                || user.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user);
        String refreshToken = generateRefreshToken();
        String tokenHash = hashRefreshToken(refreshToken);
        Instant refreshExpiresAt = Instant.now().plus(jwtProperties.getRefreshTokenTtl());

        refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, organization, tokenHash, refreshExpiresAt)
        );

        return new LoginResponse(
                accessToken.value(),
                "Bearer",
                jwtProperties.getAccessTokenTtl().toSeconds(),
                refreshToken,
                new LoginResponse.AuthenticatedUser(
                        user.getId(),
                        organization.getId(),
                        user.getRole()
                )
        );
    }

    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        RefreshToken currentToken = refreshTokenRepository.findByTokenHashForUpdate(
                        hashRefreshToken(rawRefreshToken))
                .filter(token -> token.isUsableAt(Instant.now()))
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = currentToken.getUser();
        Organization organization = currentToken.getOrganization();
        if (user.getStatus() != UserStatus.ACTIVE
                || organization.getStatus() != OrganizationStatus.ACTIVE
                || !user.getOrganization().getId().equals(organization.getId())) {
            throw new InvalidRefreshTokenException();
        }

        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user);
        String rawReplacementToken = generateRefreshToken();
        RefreshToken replacementToken = refreshTokenRepository.saveAndFlush(new RefreshToken(
                user,
                organization,
                hashRefreshToken(rawReplacementToken),
                Instant.now().plus(jwtProperties.getRefreshTokenTtl())
        ));
        currentToken.revoke(replacementToken);
        refreshTokenRepository.saveAndFlush(currentToken);

        return new LoginResponse(
                accessToken.value(),
                "Bearer",
                jwtProperties.getAccessTokenTtl().toSeconds(),
                rawReplacementToken,
                new LoginResponse.AuthenticatedUser(
                        user.getId(),
                        organization.getId(),
                        user.getRole()
                )
        );
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeActiveTokensForUser(userId, Instant.now());
    }

    private static String generateRefreshToken() {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    static String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
