package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PackagedApplicationIT {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private Process application;
    private String baseUrl;

    @TempDir
    Path temporaryDirectory;

    @Test
    void deliversAfterTransientFailureAndPreservesHistoryAfterRestart() throws Exception {
        AtomicInteger received = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> identifiers = new CopyOnWriteArrayList<>();
        HttpServer recipient = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        recipient.createContext("/webhook", exchange -> {
            try (exchange) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                identifiers.add(exchange.getRequestHeaders().getFirst("X-HookRelay-Delivery-Id"));
                exchange.sendResponseHeaders(received.incrementAndGet() == 1 ? 503 : 204, -1);
            }
        });
        recipient.start();
        String targetUrl = "http://127.0.0.1:" + recipient.getAddress().getPort() + "/webhook";
        try {
            startApplication(targetUrl);
            String event = "{\"event\":\"order.created\",\"payload\":{\"orderId\":42}}";
            HttpResponse<String> accepted = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/webhooks"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(event)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(202);
            String id = objectMapper.readTree(accepted.body()).get("deliveryId").asText();
            assertThat(accepted.headers().firstValue("Location").orElseThrow())
                    .isEqualTo("/api/webhooks/" + id);

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                JsonNode details = readJson("/api/webhooks/" + id);
                assertThat(details.get("status").asText()).isEqualTo("SUCCESS");
                assertThat(details.get("attemptCount").asInt()).isEqualTo(2);
            });

            JsonNode history = readJson("/api/webhooks/" + id + "/attempts");
            assertThat(history.size()).isEqualTo(2);
            assertThat(history.get(0).get("attemptNumber").asInt()).isEqualTo(1);
            assertThat(history.get(0).get("httpStatus").asInt()).isEqualTo(503);
            assertThat(history.get(1).get("attemptNumber").asInt()).isEqualTo(2);
            assertThat(history.get(1).get("httpStatus").asInt()).isEqualTo(204);
            Instant first = Instant.parse(history.get(0).get("finishedAt").asText());
            Instant second = Instant.parse(history.get(1).get("finishedAt").asText());
            assertThat(Duration.between(first, second)).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
            assertThat(identifiers).containsExactly(id, id);
            assertThat(received.get()).isEqualTo(2);
            for (String body : bodies) {
                assertThat(objectMapper.readTree(body)).isEqualTo(objectMapper.readTree(event));
            }

            stopApplication();
            startApplication(targetUrl);
            assertThat(readJson("/api/webhooks/" + id).get("status").asText()).isEqualTo("SUCCESS");
            assertThat(readJson("/api/webhooks/" + id + "/attempts")).isEqualTo(history);
            assertThat(received.get()).isEqualTo(2);
        } catch (Exception | AssertionError failure) {
            Path log = temporaryDirectory.resolve("application.log");
            if (Files.exists(log)) {
                System.err.println(Files.readString(log));
            }
            throw failure;
        } finally {
            stopApplication();
            recipient.stop(0);
        }
    }

    private void startApplication(String targetUrl) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-jar",
                "target/hookrelayapp-1.0.0.jar", "--server.address=127.0.0.1", "--server.port=" + port);
        builder.environment().put("DB_URL", TestDatabase.url());
        builder.environment().put("DB_USER", "postgres");
        builder.environment().put("DB_PASSWORD", "");
        builder.environment().put("WEBHOOK_TARGET_URL", targetUrl);
        builder.environment().put("DELIVERY_WORKER_ENABLED", "true");
        builder.environment().put("DELIVERY_POLL_INTERVAL_MS", "100");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(temporaryDirectory.resolve("application.log").toFile()));
        application = builder.start();
        await().ignoreExceptions().pollInterval(Duration.ofMillis(200)).atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(application.isAlive()).isTrue();
                    assertThat(readJson("/actuator/health").get("status").asText()).isEqualTo("UP");
                });
    }

    private JsonNode readJson(String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void stopApplication() throws InterruptedException {
        if (application != null && application.isAlive()) {
            application.destroy();
            if (!application.waitFor(10, TimeUnit.SECONDS)) {
                application.destroyForcibly();
                application.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
