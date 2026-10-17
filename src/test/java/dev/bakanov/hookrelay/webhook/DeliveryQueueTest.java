package dev.bakanov.hookrelay.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryQueueTest {

    private final DataSource dataSource = TestDatabase.dataSource();
    private final JdbcClient jdbcClient = JdbcClient.create(dataSource);
    private final DeliveryRepository repository = new DeliveryRepository(jdbcClient);
    private final TransactionTemplate transaction = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));

    @BeforeEach
    void clearQueue() {
        jdbcClient.sql("DELETE FROM deliveries").update();
    }

    @Test
    void skipsLockedDeliveryAndReleasesItAfterRollback() throws Exception {
        UUID id = repository.create(new WebhookRequest("order.created", new ObjectMapper().createObjectNode()),
                URI.create("http://localhost:9000/webhook"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> transaction.execute(status -> {
                PendingDelivery delivery = repository.lockNext().orElseThrow();
                repository.complete(delivery.id(), 204);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lock release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                status.setRollbackOnly();
                return delivery.id();
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(transaction.execute(status -> repository.lockNext())).isEmpty();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(repository.findById(id).orElseThrow().status()).isEqualTo("PENDING");
            assertThat(transaction.execute(status -> repository.lockNext()).orElseThrow().id()).isEqualTo(id);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void returnsEmptyWhenQueueIsEmpty() {
        assertThat(transaction.execute(status -> repository.lockNext())).isEmpty();
    }
}
