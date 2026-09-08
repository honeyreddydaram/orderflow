package com.orderflow.order.config;

import com.orderflow.common.security.JwtAuthenticationFilter;
import com.orderflow.common.security.JwtValidator;
import com.orderflow.common.web.CorrelationIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtValidator jwtValidator) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // See docs/architecture.md section 13 - a sendError() forwards internally to
                        // /error, re-entering this same filter chain as a second dispatch.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Every /api/v1/orders/** endpoint requires authentication - ownership
                        // (order belongs to the caller, or caller is ADMIN) is a service-layer
                        // check, not expressible as a URL-level rule.
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtValidator), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
