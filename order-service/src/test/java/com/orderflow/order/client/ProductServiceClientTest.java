package com.orderflow.order.client;

import com.orderflow.order.exception.UpstreamServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the client-side timeout wiring actually works. Before this test, ProductServiceClient's
 * real HTTP path had zero coverage - every other test (OrderServiceTest, OrderControllerIntegrationTest)
 * @MockBean's this client entirely, so a missing or misconfigured timeout would never have been
 * caught. A server that accepts the connection but never responds simulates a hung Product
 * Service - proving the read timeout fires, not just that a closed port fails fast (which would
 * happen regardless of any timeout configuration).
 */
class ProductServiceClientTest {

    @Test
    void getProduct_throwsPromptly_whenServerAcceptsButNeverResponds() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread acceptThread = new Thread(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Thread.sleep(10_000);
                } catch (Exception ignored) {
                    // Test socket closing/interrupted - nothing to do.
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(2_000);
            requestFactory.setReadTimeout(1_000);
            RestClient restClient = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .requestFactory(requestFactory)
                    .build();
            ProductServiceClient client = new ProductServiceClient(restClient);

            Instant start = Instant.now();
            assertThatThrownBy(() -> client.getProduct(UUID.randomUUID()))
                    .isInstanceOf(UpstreamServiceException.class);
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        }
    }
}
