package dev.bakanov.hookrelay.webhook;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

final class TestDatabase {

    private static final EmbeddedPostgres postgres = start();

    private TestDatabase() {
    }

    static String url() {
        return "jdbc:postgresql://127.0.0.1:" + postgres.getPort() + "/postgres";
    }

    static DataSource dataSource() {
        return postgres.getPostgresDatabase();
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres database = EmbeddedPostgres.start();
            Flyway.configure().dataSource(database.getPostgresDatabase()).load().migrate();
            return database;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
