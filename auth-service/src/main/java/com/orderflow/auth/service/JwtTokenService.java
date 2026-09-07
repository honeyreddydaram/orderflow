package com.orderflow.auth.service;

import com.orderflow.auth.config.JwtProperties;
import com.orderflow.auth.domain.User;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/** Issues short-lived RS256 access tokens for authenticated users. See docs/architecture.md section 13. */
@Service
public class JwtTokenService {

    private final RSAPrivateKey privateKey;
    private final JwtProperties properties;

    public JwtTokenService(RSAPrivateKey privateKey, JwtProperties properties) {
        this.privateKey = privateKey;
        this.properties = properties;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Enum::name).toList();

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                .signWith(privateKey)
                .compact();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtlSeconds();
    }
}
