package com.nexusgate.management.security;

import com.nexusgate.management.config.JwtProperties;
import com.nexusgate.management.service.JwtPrincipalLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final JwtPrincipalLoader principalLoader;

    public JwtAuthenticationFilter(
            JwtProperties properties,
            SecretKey signingKey,
            JwtPrincipalLoader principalLoader) {
        this.properties = properties;
        this.signingKey = signingKey;
        this.principalLoader = principalLoader;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        System.out.println("SHOULD NOT FILTER CHECK FROM JWT FILTER");
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean check = request.getMethod().equals("POST")
                && request.getRequestURI()
                .equals("/api/v1/api-keys/internal/validate");
        System.out.println("Method : " + method);
        System.out.println("Path : " + path);
        System.out.println("SHOULD NOT FILTER: " + check);
        return check;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        System.out.println("JWT FILTER EXECUTING");
        String authorization = request.getHeader("Authorization");
        System.out.println("JWT FILTER :HEADER RECEAVED:" + authorization);
        if (authorization == null) {
            System.out.println("JWT FILTER :HEADER RECEAVED:WAS NULL" );
            filterChain.doFilter(request, response);
            return;
        }
        System.out.println("JWT FILTER :HEADER RECEAVED:WAS NOT NULL" );
        if (!authorization.startsWith("Bearer ") || authorization.length() <= "Bearer ".length()) {
            System.out.println("JWT FILTER :HEADER RECEAVED:BEARER MISSING OR INVALID" );
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .requireAudience(properties.getAudience())
                    .build()
                    .parseSignedClaims(authorization.substring("Bearer ".length()));

            if (!"HS256".equals(parsed.getHeader().getAlgorithm())) {
                reject(response);
                return;
            }

            Claims claims = parsed.getPayload();
            String userIdClaim = claims.get("userid", String.class);
            String organizationIdClaim = claims.get("organizationid", String.class);
            if (claims.getIssuedAt() == null || claims.getExpiration() == null
                    || userIdClaim == null || organizationIdClaim == null) {
                reject(response);
                return;
            }

            UUID userId = UUID.fromString(userIdClaim);
            UUID tokenOrganizationId = UUID.fromString(organizationIdClaim);
            AuthenticatedUserPrincipal principal = principalLoader.load(userId, tokenOrganizationId)
                    .orElse(null);
            if (principal == null) {
                reject(response);
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            reject(response);
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
