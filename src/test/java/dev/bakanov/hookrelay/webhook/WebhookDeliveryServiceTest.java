package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDeliveryServiceTest {

    @Test
    void mapsConnectionFailureToBadGateway() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        WebhookDeliveryService service = new WebhookDeliveryService(RestClient.builder(),
                URI.create("http://127.0.0.1:" + closedPort + "/webhook"),
                new DeliveryRepository(JdbcClient.create(TestDatabase.dataSource())));
        String event = "connection.failure." + java.util.UUID.randomUUID();
        WebhookRequest request = new WebhookRequest(event,
                new ObjectMapper().createObjectNode());

        assertThatThrownBy(() -> service.deliver(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        var saved = JdbcClient.create(TestDatabase.dataSource())
                .sql("SELECT * FROM deliveries WHERE event = :event").param("event", event)
                .query().singleRow();
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("http_status")).isNull();
        assertThat(saved.get("error_message")).isEqualTo("Webhook recipient is unavailable");
        assertThat(saved.get("completed_at")).isNotNull();
    }

    @Test
    void rejectsNonHttpTarget() {
        assertThatThrownBy(() -> new WebhookDeliveryService(RestClient.builder(),
                URI.create("file:///tmp/webhook"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
