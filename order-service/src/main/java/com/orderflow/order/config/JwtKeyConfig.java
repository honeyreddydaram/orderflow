package com.orderflow.order.config;

import com.orderflow.common.security.JwtValidator;
import com.orderflow.common.security.PemUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;

/**
 * Order Service only ever verifies tokens issued by Auth Service - it loads the RSA PUBLIC key
 * only, never a private key, and never signs anything. See docs/architecture.md section 13.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

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
