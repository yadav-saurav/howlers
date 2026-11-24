package app.howlers.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for integration tests: a real PostgreSQL (embedded binaries, no Docker needed) with Flyway
 * migrations applied, so SQL-specific behaviour (tsvector, advisory locks, ON CONFLICT) is exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTest.EmbeddedDb.class)
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    public static class EmbeddedDb {
        private static EmbeddedPostgres pg;

        @Bean
        @Primary
        DataSource dataSource() {
            synchronized (EmbeddedDb.class) {
                if (pg == null) {
                    try {
                        pg = EmbeddedPostgres.builder().start();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            return pg.getPostgresDatabase();
        }
    }
}
