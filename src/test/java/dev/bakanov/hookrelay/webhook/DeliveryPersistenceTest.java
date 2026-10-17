package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.bakanov.hookrelay.HookRelayApplication;
import java.net.URI;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryPersistenceTest {

    @Test
    void processesPendingDeliveryAfterRestart() throws Exception {
        JdbcClient.create(TestDatabase.dataSource()).sql("DELETE FROM deliveries").update();
        HttpServer recipient = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger responseStatus = new AtomicInteger(503);
        recipient.createContext("/webhook", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                sent.incrementAndGet();
                exchange.sendResponseHeaders(responseStatus.get(), -1);
            }
        });
        recipient.start();
        try {
            UUID id;
            try (ConfigurableApplicationContext first = startApplication()) {
                id = first.getBean(DeliveryRepository.class).create(
                        new WebhookRequest("order.created", new ObjectMapper().createObjectNode()),
                        URI.create("http://127.0.0.1:" + recipient.getAddress().getPort() + "/webhook"));
                assertThat(first.getBean(WebhookDeliveryService.class).processNext()).isTrue();
                first.getBean(JdbcClient.class).sql("""
                        UPDATE deliveries SET next_attempt_at = clock_timestamp() + INTERVAL '1 hour'
                        WHERE id = :id
                        """).param("id", id).update();
            }
            try (ConfigurableApplicationContext second = startApplication()) {
                WebhookDeliveryService service = second.getBean(WebhookDeliveryService.class);
                assertThat(service.findById(id).status()).isEqualTo("PENDING");
                assertThat(service.findById(id).attemptCount()).isEqualTo(1);
                assertThat(service.findById(id).nextAttemptAt()).isNotNull();
                assertThat(service.processNext()).isFalse();
                second.getBean(JdbcClient.class).sql("""
                        UPDATE deliveries SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                        WHERE id = :id
                        """).param("id", id).update();
                responseStatus.set(204);
                assertThat(service.processNext()).isTrue();
                assertThat(service.findById(id).status()).isEqualTo("SUCCESS");
                assertThat(service.findById(id).attemptCount()).isEqualTo(2);
                assertThat(service.processNext()).isFalse();
            }
            assertThat(sent.get()).isEqualTo(2);
        } finally {
            recipient.stop(0);
        }
    }

    @Test
    void retainsDeliveryAfterApplicationRestart() {
        UUID id;
        try (ConfigurableApplicationContext first = startApplication()) {
            DeliveryRepository repository = first.getBean(DeliveryRepository.class);
            id = repository.create(new WebhookRequest("order.created",
                    new ObjectMapper().createObjectNode().put("orderId", 42)),
                    URI.create("http://localhost:9000/webhook"));
            assertThat(first.getBean(JdbcClient.class)
                    .sql("SELECT status FROM deliveries WHERE id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo("PENDING");
            repository.complete(id, 204);
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            var saved = second.getBean(JdbcClient.class)
                    .sql("SELECT * FROM deliveries WHERE id = :id").param("id", id)
                    .query().singleRow();
            assertThat(saved.get("status")).isEqualTo("SUCCESS");
            assertThat(saved.get("http_status")).isEqualTo(204);
            assertThat(saved.get("event")).isEqualTo("order.created");
            assertThat(saved.get("completed_at")).isNotNull();
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(HookRelayApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + TestDatabase.url(),
                        "--spring.datasource.username=postgres", "--spring.datasource.password=",
                        "--hookrelay.delivery.worker-enabled=false");
    }
}
