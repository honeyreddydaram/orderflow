package com.orderflow.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

/**
 * Stateless verifier for access tokens issued by the Auth Service. Resource-server filters in
 * every other service use this to validate a bearer token locally (no network call to Auth) and
 * populate the Spring Security context. See docs/architecture.md section 13.
 */
public class JwtValidator {

    private static final String ROLES_CLAIM = "roles";
    private static final String USERNAME_CLAIM = "username";

    private final RSAPublicKey publicKey;

    public JwtValidator(RSAPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @SuppressWarnings("unchecked")
    public JwtClaims validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID userId = UUID.fromString(claims.getSubject());
            String username = claims.get(USERNAME_CLAIM, String.class);
            List<String> roles = claims.get(ROLES_CLAIM, List.class);
            return new JwtClaims(userId, username, roles == null ? List.of() : roles);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid or expired access token", e);
        }
    }
}
