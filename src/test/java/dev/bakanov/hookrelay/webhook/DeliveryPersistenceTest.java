package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bakanov.hookrelay.HookRelayApplication;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryPersistenceTest {

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
                        "--spring.datasource.username=postgres", "--spring.datasource.password=");
    }
}
