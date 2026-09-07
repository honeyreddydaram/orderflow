package com.orderflow.auth.service;

import com.orderflow.auth.config.JwtProperties;
import com.orderflow.auth.domain.Role;
import com.orderflow.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    @Test
    void generateAccessToken_encodesSubjectUsernameRolesAndExpiry() {
        KeyPair keyPair = Keys.keyPairFor(io.jsonwebtoken.SignatureAlgorithm.RS256);
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties properties = new JwtProperties(null, null, 900, 1_209_600);
        JwtTokenService service = new JwtTokenService(privateKey, properties);

        User user = User.register("alice", "alice@example.com", "hashed");
        user.getRoles().add(Role.ADMIN);

        String token = service.generateAccessToken(user);

        Claims claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("roles", List.class)).containsExactlyInAnyOrder("CUSTOMER", "ADMIN");
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
        assertThat(service.accessTokenTtlSeconds()).isEqualTo(900);
    }
}
