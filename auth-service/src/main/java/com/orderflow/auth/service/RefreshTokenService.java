package com.orderflow.auth.service;

import com.orderflow.auth.config.JwtProperties;
import com.orderflow.auth.domain.RefreshToken;
import com.orderflow.auth.domain.User;
import com.orderflow.auth.repository.RefreshTokenRepository;
import com.orderflow.common.security.InvalidTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and rotates opaque refresh tokens. Only a SHA-256 hash of the token is ever persisted
 * (see {@link RefreshToken}); the raw value is returned to the caller exactly once, at issuance.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties properties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();
        Instant expiresAt = Instant.now().plus(properties.refreshTokenTtlSeconds(), ChronoUnit.SECONDS);
        refreshTokenRepository.save(RefreshToken.issue(user, hash(rawToken), expiresAt));
        return rawToken;
    }

    /** Validates and revokes {@code rawToken}, returning the user it belonged to. */
    @Transactional
    public User consume(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(RefreshToken::isValid)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired refresh token", null));
        token.setRevoked(true);
        return token.getUser();
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> token.setRevoked(true));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
