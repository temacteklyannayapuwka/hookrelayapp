package dev.bakanov.hookrelay.webhook;

import java.net.URI;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {

    private final JdbcClient jdbcClient;

    public DeliveryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<DeliveryDetails> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, event, status, http_status, error_message, created_at, completed_at
                FROM deliveries WHERE id = :id
                """)
                .param("id", id)
                .query((result, rowNumber) -> {
                    Timestamp completedAt = result.getTimestamp("completed_at");
                    return new DeliveryDetails(result.getObject("id", UUID.class),
                            result.getString("event"), result.getString("status"),
                            result.getObject("http_status", Integer.class), result.getString("error_message"),
                            result.getTimestamp("created_at").toInstant(),
                            completedAt == null ? null : completedAt.toInstant());
                })
                .optional();
    }

    public UUID create(WebhookRequest request, URI targetUrl) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO deliveries (id, event, payload, target_url, status)
                VALUES (:id, :event, CAST(:payload AS JSONB), :targetUrl, 'PENDING')
                """)
                .param("id", id)
                .param("event", request.event())
                .param("payload", request.payload().toString())
                .param("targetUrl", targetUrl.toString())
                .update();
        return id;
    }

    public void complete(UUID id, int httpStatus) {
        jdbcClient.sql("""
                UPDATE deliveries
                SET status = 'SUCCESS', http_status = :httpStatus, completed_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("httpStatus", httpStatus)
                .update();
    }

    public void fail(UUID id, Integer httpStatus, String errorMessage) {
        jdbcClient.sql("""
                UPDATE deliveries
                SET status = 'FAILED', http_status = :httpStatus, error_message = :errorMessage,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("httpStatus", httpStatus, java.sql.Types.INTEGER)
                .param("errorMessage", errorMessage)
                .update();
    }
}
