package com.orderflow.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidatorTest {

    private RSAPrivateKey privateKey;
    private JwtValidator validator;

    @BeforeEach
    void setUp() {
        KeyPair keyPair = Keys.keyPairFor(io.jsonwebtoken.SignatureAlgorithm.RS256);
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        validator = new JwtValidator((RSAPublicKey) keyPair.getPublic());
    }

    @Test
    void validate_acceptsWellSignedToken() {
        UUID userId = UUID.randomUUID();
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim("username", "alice")
                .claim("roles", List.of("CUSTOMER"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(privateKey)
                .compact();

        JwtClaims claims = validator.validate(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void validate_rejectsExpiredToken() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("username", "alice")
                .claim("roles", List.of("CUSTOMER"))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .signWith(privateKey)
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validate_rejectsTokenSignedByAnotherKey() {
        KeyPair otherKeyPair = Keys.keyPairFor(io.jsonwebtoken.SignatureAlgorithm.RS256);
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("username", "alice")
                .claim("roles", List.of("CUSTOMER"))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(otherKeyPair.getPrivate())
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(InvalidTokenException.class);
    }
}
