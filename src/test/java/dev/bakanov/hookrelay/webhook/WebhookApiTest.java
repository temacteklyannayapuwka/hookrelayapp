package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookApiTest {

    private static final AtomicInteger responseStatus = new AtomicInteger(204);
    private static final AtomicInteger deliveries = new AtomicInteger();
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private static final AtomicReference<String> receivedContentType = new AtomicReference<>();
    private static final HttpServer recipient = startRecipient();

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void configureTarget(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("hookrelay.delivery.target-url",
                () -> "http://127.0.0.1:" + recipient.getAddress().getPort() + "/webhook");
    }

    @BeforeEach
    void resetRecipient() {
        jdbcClient.sql("DELETE FROM deliveries").update();
        responseStatus.set(204);
        deliveries.set(0);
        receivedBody.set(null);
        receivedMethod.set(null);
        receivedContentType.set(null);
    }

    @AfterAll
    static void stopRecipient() {
        recipient.stop(0);
    }

    @Test
    void deliversEventAsJson() throws Exception {
        String request = """
                {"event":"order.created","payload":{"orderId":42}}
                """;

        ResponseEntity<JsonNode> response = post(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("delivered").asBoolean()).isTrue();
        assertThat(response.getBody().get("httpStatus").asInt()).isEqualTo(204);
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedContentType.get()).isEqualTo("application/json");
        assertThat(objectMapper.readTree(receivedBody.get()))
                .isEqualTo(objectMapper.readTree(request));
        UUID id = UUID.fromString(response.getBody().get("deliveryId").asText());
        var saved = jdbcClient.sql("SELECT *, payload::text AS payload_json FROM deliveries WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(saved.get("event")).isEqualTo("order.created");
        assertThat(objectMapper.readTree((String) saved.get("payload_json")))
                .isEqualTo(objectMapper.readTree("{\"orderId\":42}"));
        assertThat(saved.get("status")).isEqualTo("SUCCESS");
        assertThat(saved.get("http_status")).isEqualTo(204);
        assertThat(saved.get("created_at")).isNotNull();
        assertThat(saved.get("completed_at")).isNotNull();
        assertThat(saved.get("error_message")).isNull();
    }

    @Test
    void returnsBadGatewayWhenRecipientFails() {
        responseStatus.set(500);

        assertThat(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getStatusCode().value()).isEqualTo(502);
        assertThat(deliveries.get()).isEqualTo(1);
        var saved = jdbcClient.sql("SELECT * FROM deliveries").query().singleRow();
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("http_status")).isEqualTo(500);
        assertThat(saved.get("error_message")).isEqualTo("Webhook recipient returned HTTP 500");
        assertThat(saved.get("completed_at")).isNotNull();
    }

    @Test
    void doesNotFollowRedirects() {
        responseStatus.set(307);

        assertThat(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getStatusCode().value()).isEqualTo(502);
        assertThat(deliveries.get()).isEqualTo(1);
    }

    @Test
    void rejectsBlankEvent() {
        assertThat(post("{\"event\":\" \",\"payload\":{}}")
                .getStatusCode().value()).isEqualTo(400);
        assertThat(deliveries.get()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM deliveries").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsMissingPayload() {
        assertThat(post("{\"event\":\"order.created\"}")
                .getStatusCode().value()).isEqualTo(400);
        assertThat(deliveries.get()).isZero();
    }

    @Test
    void rejectsMalformedJson() {
        assertThat(post("{broken").getStatusCode().value()).isEqualTo(400);
        assertThat(deliveries.get()).isZero();
    }

    @Test
    void keepsHealthEndpointAvailable() {
        ResponseEntity<JsonNode> response = client.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    private ResponseEntity<JsonNode> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client.postForEntity("/api/webhooks", new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static HttpServer startRecipient() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhook", exchange -> {
                try (exchange) {
                    deliveries.incrementAndGet();
                    receivedMethod.set(exchange.getRequestMethod());
                    receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().set("Location", "/webhook");
                    exchange.sendResponseHeaders(responseStatus.get(), -1);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
