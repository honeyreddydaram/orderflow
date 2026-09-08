package com.orderflow.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Explicit connect/read timeouts - without these, a slow or hung Product Service would block
     * Order Service's request threads for the OS-level TCP timeout (minutes), risking thread-pool
     * exhaustion across all of Order Service from a single degraded dependency.
     */
    @Bean
    public RestClient productServiceRestClient(@Value("${orderflow.services.product-service.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(5_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
