package com.orderflow.auth.controller;

import com.orderflow.auth.dto.AuthResponse;
import com.orderflow.auth.dto.LoginRequest;
import com.orderflow.auth.dto.RefreshRequest;
import com.orderflow.auth.dto.RegisterRequest;
import com.orderflow.auth.dto.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_db")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/auth";
    }

    @Test
    void fullAuthLifecycle_registerLoginMeRefreshLogout() {
        RegisterRequest registerRequest = new RegisterRequest("bob", "bob@example.com", "password123");
        ResponseEntity<UserProfileResponse> registerResponse =
                restTemplate.postForEntity(baseUrl() + "/register", registerRequest, UserProfileResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody().username()).isEqualTo("bob");

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl() + "/login", new LoginRequest("bob", "password123"), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse tokens = loginResponse.getBody();
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(tokens.accessToken());
        ResponseEntity<UserProfileResponse> meResponse = restTemplate.exchange(
                baseUrl() + "/me", HttpMethod.GET, new HttpEntity<>(authHeaders), UserProfileResponse.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody().username()).isEqualTo("bob");

        ResponseEntity<AuthResponse> refreshResponse = restTemplate.postForEntity(
                baseUrl() + "/refresh", new RefreshRequest(tokens.refreshToken()), AuthResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse rotated = refreshResponse.getBody();
        assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());

        // Old refresh token was rotated out - reusing it must now fail.
        ResponseEntity<String> reuseResponse = restTemplate.postForEntity(
                baseUrl() + "/refresh", new RefreshRequest(tokens.refreshToken()), String.class);
        assertThat(reuseResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                baseUrl() + "/logout", new RefreshRequest(rotated.refreshToken()), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                baseUrl() + "/refresh", new RefreshRequest(rotated.refreshToken()), String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_rejectsDuplicateUsername_withConflict() {
        RegisterRequest request = new RegisterRequest("carol", "carol@example.com", "password123");
        restTemplate.postForEntity(baseUrl() + "/register", request, UserProfileResponse.class);

        ResponseEntity<String> secondAttempt = restTemplate.postForEntity(
                baseUrl() + "/register",
                new RegisterRequest("carol", "carol2@example.com", "password123"),
                String.class);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void me_rejectsRequestWithoutToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_rejectsInvalidPayload_withBadRequest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/register", new RegisterRequest("ab", "not-an-email", "short"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
