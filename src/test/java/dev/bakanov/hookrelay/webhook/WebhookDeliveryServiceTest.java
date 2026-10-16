package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
                URI.create("http://127.0.0.1:" + closedPort + "/webhook"));
        WebhookRequest request = new WebhookRequest("order.created",
                new ObjectMapper().createObjectNode());

        assertThatThrownBy(() -> service.deliver(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void rejectsNonHttpTarget() {
        assertThatThrownBy(() -> new WebhookDeliveryService(RestClient.builder(),
                URI.create("file:///tmp/webhook")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
