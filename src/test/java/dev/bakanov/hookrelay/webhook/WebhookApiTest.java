package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final AtomicReference<String> receivedDeliveryId = new AtomicReference<>();
    private static final HttpServer recipient = startRecipient();

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @DynamicPropertySource
    static void configureTarget(DynamicPropertyRegistry registry) {
        registry.add("hookrelay.delivery.worker-enabled", () -> "false");
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
        receivedDeliveryId.set(null);
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

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().get("status").asText()).isEqualTo("PENDING");
        UUID id = UUID.fromString(response.getBody().get("deliveryId").asText());
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/api/webhooks/" + id));
        assertThat(deliveries.get()).isZero();
        assertThat(deliveryService.findById(id).status()).isEqualTo("PENDING");
        assertThat(deliveryService.processNext()).isTrue();
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedContentType.get()).isEqualTo("application/json");
        assertThat(objectMapper.readTree(receivedBody.get()))
                .isEqualTo(objectMapper.readTree(request));
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

        ResponseEntity<JsonNode> details = client.getForEntity("/api/webhooks/" + id, JsonNode.class);
        assertThat(details.getStatusCode().value()).isEqualTo(200);
        assertThat(details.getBody().get("deliveryId").asText()).isEqualTo(id.toString());
        assertThat(details.getBody().get("event").asText()).isEqualTo("order.created");
        assertThat(details.getBody().get("status").asText()).isEqualTo("SUCCESS");
        assertThat(details.getBody().get("httpStatus").asInt()).isEqualTo(204);
        assertThat(details.getBody().get("attemptCount").asInt()).isEqualTo(1);
        assertThat(details.getBody().get("nextAttemptAt").isNull()).isTrue();
        assertThat(details.getBody().get("errorMessage").isNull()).isTrue();
        assertThat(Instant.parse(details.getBody().get("createdAt").asText()))
                .isEqualTo(((java.sql.Timestamp) saved.get("created_at")).toInstant());
        assertThat(Instant.parse(details.getBody().get("completedAt").asText()))
                .isEqualTo(((java.sql.Timestamp) saved.get("completed_at")).toInstant());
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(deliveryService.processNext()).isFalse();
        assertThat(deliveries.get()).isEqualTo(1);
    }

    @Test
    void recordsFailureAfterAcceptingEvent() {
        responseStatus.set(400);

        assertThat(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getStatusCode().value()).isEqualTo(202);
        assertThat(deliveryService.processNext()).isTrue();
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(deliveryService.processNext()).isFalse();
        assertThat(deliveries.get()).isEqualTo(1);
        var saved = jdbcClient.sql("SELECT * FROM deliveries").query().singleRow();
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("http_status")).isEqualTo(400);
        assertThat(saved.get("error_message")).isEqualTo("Webhook recipient returned HTTP 400");
        assertThat(saved.get("completed_at")).isNotNull();

        ResponseEntity<JsonNode> details = client.getForEntity("/api/webhooks/" + saved.get("id"),
                JsonNode.class);
        assertThat(details.getStatusCode().value()).isEqualTo(200);
        assertThat(details.getBody().get("status").asText()).isEqualTo("FAILED");
        assertThat(details.getBody().get("httpStatus").asInt()).isEqualTo(400);
        assertThat(details.getBody().get("errorMessage").asText())
                .isEqualTo("Webhook recipient returned HTTP 400");
        assertThat(deliveries.get()).isEqualTo(1);
    }

    @Test
    void returnsPendingDeliveryWithoutSendingIt() {
        UUID id = new DeliveryRepository(jdbcClient).create(
                new WebhookRequest("order.created", objectMapper.createObjectNode()),
                URI.create("http://localhost:9000/webhook"));

        ResponseEntity<JsonNode> response = client.getForEntity("/api/webhooks/" + id, JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(response.getBody().get("httpStatus").isNull()).isTrue();
        assertThat(response.getBody().get("errorMessage").isNull()).isTrue();
        assertThat(response.getBody().get("completedAt").isNull()).isTrue();
        assertThat(response.getBody().get("createdAt").asText()).isNotBlank();
        assertThat(response.getBody().get("attemptCount").asInt()).isZero();
        assertThat(response.getBody().get("nextAttemptAt").asText()).isNotBlank();
        assertThat(deliveries.get()).isZero();
        assertThat(jdbcClient.sql("SELECT status FROM deliveries WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void returnsNotFoundForUnknownDelivery() {
        ResponseEntity<JsonNode> response = client.getForEntity("/api/webhooks/" + UUID.randomUUID(),
                JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(deliveries.get()).isZero();
    }

    @Test
    void rejectsMalformedDeliveryId() {
        ResponseEntity<JsonNode> response = client.getForEntity("/api/webhooks/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(deliveries.get()).isZero();
    }

    @Test
    void doesNotFollowRedirects() {
        responseStatus.set(307);

        assertThat(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getStatusCode().value()).isEqualTo(202);
        assertThat(deliveryService.processNext()).isTrue();
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT status FROM deliveries").query(String.class).single())
                .isEqualTo("FAILED");
        assertThat(jdbcClient.sql("SELECT http_status FROM deliveries").query(Integer.class).single())
                .isEqualTo(307);
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

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void retriesTransientFailureAndThenSucceeds(int httpStatus) {
        responseStatus.set(httpStatus);
        UUID id = UUID.fromString(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getBody().get("deliveryId").asText());

        assertThat(deliveryService.processNext()).isTrue();
        DeliveryDetails pending = deliveryService.findById(id);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertThat(pending.nextAttemptAt()).isAfter(Instant.now());
        assertThat(receivedDeliveryId.get()).isEqualTo(id.toString());
        assertThat(deliveryService.processNext()).isFalse();
        assertThat(deliveries.get()).isEqualTo(1);
        makeDue(id);
        responseStatus.set(204);

        assertThat(deliveryService.processNext()).isTrue();
        DeliveryDetails completed = deliveryService.findById(id);
        assertThat(completed.status()).isEqualTo("SUCCESS");
        assertThat(completed.attemptCount()).isEqualTo(2);
        assertThat(completed.nextAttemptAt()).isNull();
        assertThat(deliveryService.processNext()).isFalse();
        assertThat(deliveries.get()).isEqualTo(2);
        assertThat(receivedDeliveryId.get()).isEqualTo(id.toString());
        assertThat(jdbcClient.sql("""
                SELECT http_status FROM delivery_attempts WHERE delivery_id = :id ORDER BY attempt_number
                """).param("id", id).query(Integer.class).list()).containsExactly(httpStatus, 204);
    }

    @Test
    void stopsAfterThreeAttempts() {
        responseStatus.set(503);
        UUID id = UUID.fromString(post("{\"event\":\"order.created\",\"payload\":{}}")
                .getBody().get("deliveryId").asText());
        Instant firstRetry = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(deliveryService.processNext()).isTrue();
            DeliveryDetails details = deliveryService.findById(id);
            assertThat(details.attemptCount()).isEqualTo(attempt);
            assertThat(deliveryService.processNext()).isFalse();
            if (attempt < 3) {
                assertThat(details.status()).isEqualTo("PENDING");
                assertThat(details.nextAttemptAt()).isAfter(Instant.now());
                if (firstRetry != null) {
                    assertThat(details.nextAttemptAt()).isAfter(firstRetry);
                }
                firstRetry = details.nextAttemptAt();
                makeDue(id);
            }
        }
        DeliveryDetails failed = deliveryService.findById(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.httpStatus()).isEqualTo(503);
        assertThat(failed.nextAttemptAt()).isNull();
        assertThat(failed.completedAt()).isNotNull();
        assertThat(deliveries.get()).isEqualTo(3);
        assertThat(jdbcClient.sql("SELECT count(*) FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(3L);
    }

    private void makeDue(UUID id) {
        jdbcClient.sql("""
                UPDATE deliveries SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id = :id
                """).param("id", id).update();
    }

    private static HttpServer startRecipient() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhook", exchange -> {
                try (exchange) {
                    deliveries.incrementAndGet();
                    receivedMethod.set(exchange.getRequestMethod());
                    receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    receivedDeliveryId.set(exchange.getRequestHeaders().getFirst("X-HookRelay-Delivery-Id"));
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
