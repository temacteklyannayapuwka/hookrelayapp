package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryWorkerTest {

    private static final CountDownLatch received = new CountDownLatch(1);
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final AtomicInteger deliveries = new AtomicInteger();
    private static final AtomicReference<String> deliveryHeader = new AtomicReference<>();
    private static final HttpServer recipient = startRecipient();

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("hookrelay.delivery.worker-enabled", () -> "true");
        registry.add("hookrelay.delivery.poll-interval-ms", () -> "50");
        registry.add("hookrelay.delivery.target-url",
                () -> "http://127.0.0.1:" + recipient.getAddress().getPort() + "/webhook");
    }

    @AfterAll
    static void stopRecipient() {
        release.countDown();
        recipient.stop(0);
    }

    @Test
    void acceptsBeforeRecipientRespondsAndDeliversInBackground() throws Exception {
        jdbcClient.sql("DELETE FROM deliveries").update();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> accepted = client.postForEntity("/api/webhooks",
                new HttpEntity<>("{\"event\":\"order.created\",\"payload\":{\"orderId\":42}}", headers),
                JsonNode.class);

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String id = accepted.getBody().get("deliveryId").asText();
        try {
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.getForObject("/api/webhooks/" + id, JsonNode.class)
                    .get("status").asText()).isEqualTo("PENDING");
            assertThat(deliveryHeader.get()).isEqualTo(id);
        } finally {
            release.countDown();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode details = client.getForObject("/api/webhooks/" + id, JsonNode.class);
            assertThat(details.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(details.get("httpStatus").asInt()).isEqualTo(204);
        });
        assertThat(deliveries.get()).isEqualTo(1);
    }

    private static HttpServer startRecipient() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhook", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    deliveries.incrementAndGet();
                    deliveryHeader.set(exchange.getRequestHeaders().getFirst("X-HookRelay-Delivery-Id"));
                    received.countDown();
                    try {
                        if (!release.await(8, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(504, -1);
                            return;
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
