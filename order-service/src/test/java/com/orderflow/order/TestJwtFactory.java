package com.orderflow.order;

import com.orderflow.common.security.PemUtils;
import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints access tokens for tests using the test-fixture private key at
 * src/test/resources/keys/private_key.pem. This key never ships in the application - Order
 * Service only ever loads the matching public key at runtime (see JwtKeyConfig).
 */
public final class TestJwtFactory {

    private static final RSAPrivateKey PRIVATE_KEY = loadPrivateKey();

    private TestJwtFactory() {
    }

    public static String token(UUID userId, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "test-user")
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .signWith(PRIVATE_KEY)
                .compact();
    }

    public static String customerToken(UUID userId) {
        return token(userId, List.of("CUSTOMER"));
    }

    public static String adminToken(UUID userId) {
        return token(userId, List.of("ADMIN"));
    }

    private static RSAPrivateKey loadPrivateKey() {
        try {
            String pem = java.nio.file.Files.readString(java.nio.file.Path.of(
                    TestJwtFactory.class.getClassLoader().getResource("keys/private_key.pem").toURI()));
            return PemUtils.readPrivateKey(pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
