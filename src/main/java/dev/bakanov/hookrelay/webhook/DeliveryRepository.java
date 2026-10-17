package dev.bakanov.hookrelay.webhook;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
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
                SELECT id, event, status, http_status, error_message, created_at, completed_at,
                    attempt_count, next_attempt_at
                FROM deliveries WHERE id = :id
                """)
                .param("id", id)
                .query((result, rowNumber) -> {
                    Timestamp completedAt = result.getTimestamp("completed_at");
                    Timestamp nextAttemptAt = result.getTimestamp("next_attempt_at");
                    return new DeliveryDetails(result.getObject("id", UUID.class),
                            result.getString("event"), result.getString("status"),
                            result.getObject("http_status", Integer.class), result.getString("error_message"),
                            result.getTimestamp("created_at").toInstant(),
                            completedAt == null ? null : completedAt.toInstant(), result.getInt("attempt_count"),
                            nextAttemptAt == null ? null : nextAttemptAt.toInstant());
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

    public Optional<PendingDelivery> lockNext() {
        return jdbcClient.sql("""
                SELECT id, target_url, jsonb_build_object('event', event, 'payload', payload)::text AS body
                FROM deliveries
                WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp() AND attempt_count < 3
                ORDER BY next_attempt_at, created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)
                .query((result, rowNumber) -> new PendingDelivery(result.getObject("id", UUID.class),
                        result.getString("target_url"), result.getString("body")))
                .optional();
    }

    public void complete(UUID id, int httpStatus) {
        jdbcClient.sql("""
                UPDATE deliveries
                SET status = 'SUCCESS', http_status = :httpStatus, completed_at = clock_timestamp(),
                    next_attempt_at = NULL
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
                    completed_at = clock_timestamp(), next_attempt_at = NULL
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("httpStatus", httpStatus, java.sql.Types.INTEGER)
                .param("errorMessage", errorMessage)
                .update();
    }

    public int recordAttempt(UUID id, Integer httpStatus, String errorMessage) {
        int attempt = jdbcClient.sql("""
                UPDATE deliveries SET attempt_count = attempt_count + 1
                WHERE id = :id AND status = 'PENDING' AND attempt_count < 3
                RETURNING attempt_count
                """)
                .param("id", id)
                .query(Integer.class).single();
        jdbcClient.sql("""
                INSERT INTO delivery_attempts (delivery_id, attempt_number, http_status, error_message)
                VALUES (:id, :attempt, :httpStatus, :errorMessage)
                """)
                .param("id", id)
                .param("attempt", attempt)
                .param("httpStatus", httpStatus, java.sql.Types.INTEGER)
                .param("errorMessage", errorMessage, java.sql.Types.VARCHAR)
                .update();
        return attempt;
    }

    public void scheduleRetry(UUID id, Duration delay) {
        jdbcClient.sql("""
                UPDATE deliveries
                SET next_attempt_at = clock_timestamp() + (:delaySeconds * INTERVAL '1 second')
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("delaySeconds", delay.toSeconds())
                .update();
    }
}
