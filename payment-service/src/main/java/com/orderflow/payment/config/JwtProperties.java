package com.orderflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "orderflow.jwt")
public record JwtProperties(Resource publicKeyLocation) {
}
