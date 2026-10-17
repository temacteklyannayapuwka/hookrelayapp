package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDeliveryServiceTest {

    @Test
    void recordsConnectionFailure() throws Exception {
        DataSource dataSource = TestDatabase.dataSource();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DELETE FROM deliveries").update();
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        WebhookDeliveryService service = new WebhookDeliveryService(RestClient.builder(),
                URI.create("http://127.0.0.1:" + closedPort + "/webhook"),
                new DeliveryRepository(jdbcClient));
        String event = "connection.failure." + java.util.UUID.randomUUID();
        WebhookRequest request = new WebhookRequest(event,
                new ObjectMapper().createObjectNode());

        var accepted = service.enqueue(request);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        for (int attempt = 1; attempt <= 3; attempt++) {
            Boolean processed = transaction.execute(status -> service.processNext());
            assertThat(processed).isTrue();
            assertThat(service.findById(accepted.deliveryId()).attemptCount()).isEqualTo(attempt);
            Boolean immediate = transaction.execute(status -> service.processNext());
            assertThat(immediate).isFalse();
            if (attempt < 3) {
                assertThat(service.findById(accepted.deliveryId()).status()).isEqualTo("PENDING");
                jdbcClient.sql("""
                        UPDATE deliveries SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                        WHERE id = :id
                        """).param("id", accepted.deliveryId()).update();
            }
        }
        var saved = JdbcClient.create(TestDatabase.dataSource())
                .sql("SELECT * FROM deliveries WHERE event = :event").param("event", event)
                .query().singleRow();
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("http_status")).isNull();
        assertThat(saved.get("error_message")).isEqualTo("Webhook recipient is unavailable");
        assertThat(saved.get("completed_at")).isNotNull();
        assertThat(saved.get("next_attempt_at")).isNull();
        assertThat(jdbcClient.sql("SELECT count(*) FROM delivery_attempts WHERE delivery_id = :id")
                .param("id", accepted.deliveryId()).query(Long.class).single()).isEqualTo(3L);
        Boolean processedAgain = transaction.execute(status -> service.processNext());
        assertThat(processedAgain).isFalse();
    }

    @Test
    void rejectsNonHttpTarget() {
        assertThatThrownBy(() -> new WebhookDeliveryService(RestClient.builder(),
                URI.create("file:///tmp/webhook"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
