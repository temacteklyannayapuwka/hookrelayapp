package dev.bakanov.hookrelay.webhook;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRetryMigrationTest {

    @Test
    void preservesCompletedDeliveriesWhenUpgrading() {
        DataSource dataSource = TestDatabase.dataSource();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        String schema = "retry_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("2").load().migrate();
            jdbcClient.sql("""
                    INSERT INTO %s.deliveries
                        (id, event, payload, target_url, status, http_status, error_message, completed_at)
                    VALUES
                        (:success, 'success', '{}', 'http://localhost/webhook', 'SUCCESS', 204, NULL, now()),
                        (:failed, 'failed', '{}', 'http://localhost/webhook', 'FAILED', NULL, 'Offline', now()),
                        (:pending, 'pending', '{}', 'http://localhost/webhook', 'PENDING', NULL, NULL, NULL)
                    """.formatted(schema))
                    .param("success", UUID.randomUUID())
                    .param("failed", UUID.randomUUID())
                    .param("pending", UUID.randomUUID())
                    .update();

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

            var completed = jdbcClient.sql("SELECT * FROM " + schema + ".deliveries WHERE status <> 'PENDING'")
                    .query().listOfRows();
            assertThat(completed).hasSize(2);
            for (var delivery : completed) {
                assertThat(delivery.get("attempt_count")).isEqualTo(1);
                assertThat(delivery.get("next_attempt_at")).isNull();
            }
            var pending = jdbcClient.sql("SELECT * FROM " + schema + ".deliveries WHERE status = 'PENDING'")
                    .query().singleRow();
            assertThat(pending.get("attempt_count")).isEqualTo(0);
            assertThat(pending.get("next_attempt_at")).isNotNull();
            assertThat(jdbcClient.sql("SELECT count(*) FROM " + schema + ".delivery_attempts")
                    .query(Long.class).single()).isEqualTo(2L);
        } finally {
            jdbcClient.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }
}
