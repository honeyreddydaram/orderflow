package com.orderflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_setsAuthentication_forValidBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        JwtValidator validator = mock(JwtValidator.class);
        when(validator.validate("good-token")).thenReturn(new JwtClaims(userId, "alice", List.of("CUSTOMER", "ADMIN")));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer good-token");

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(userId);
        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_leavesUnauthenticated_forInvalidToken() throws Exception {
        JwtValidator validator = mock(JwtValidator.class);
        when(validator.validate(any())).thenThrow(new InvalidTokenException("bad", null));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_leavesUnauthenticated_whenNoAuthorizationHeader() throws Exception {
        JwtValidator validator = mock(JwtValidator.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
