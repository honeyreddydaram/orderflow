package com.orderflow.auth.config;

import com.orderflow.common.security.JwtValidator;
import com.orderflow.common.security.PemUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Loads the RSA signing keypair from the PEM files pointed to by {@code orderflow.jwt.*}
 * (see docker/generate-jwt-keys.sh). The public key is also used to build a {@link JwtValidator}
 * so this service can authenticate its own protected endpoints (/me, /logout) the same way
 * every downstream resource server will.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    @Bean
    public RSAPrivateKey jwtPrivateKey(JwtProperties properties) throws IOException {
        return PemUtils.readPrivateKey(readContent(properties.privateKeyLocation()));
    }

    @Bean
    public RSAPublicKey jwtPublicKey(JwtProperties properties) throws IOException {
        return PemUtils.readPublicKey(readContent(properties.publicKeyLocation()));
    }

    @Bean
    public JwtValidator jwtValidator(RSAPublicKey jwtPublicKey) {
        return new JwtValidator(jwtPublicKey);
    }

    private static String readContent(Resource resource) throws IOException {
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
