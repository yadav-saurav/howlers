package app.howlers.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The full demo seed as it runs at startup (base data + moderation history + collections + demo funding), in its own
 * PostgreSQL so the shared integration-test database stays free of demo rows.
 */
@SpringBootTest(properties = {"howlers.seed.enabled=true", "management.endpoints.web.exposure.include=health,prometheus"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability // tests disable metric exporters by default
@Import(DemoSeedIT.OwnDb.class)
class DemoSeedIT {
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoActivitySeeder activity;

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDb {
        /** Left running until the JVM exits: the cached Spring context (and its scheduled jobs) outlives this class. */
        static EmbeddedPostgres pg;

        @Bean
        @Primary
        DataSource dataSource() {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return pg.getPostgresDatabase();
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void seedsAModestModerationHistoryCollectionsAndADemoPoolOnce() throws Exception {
        // Moderator teams with a small, real history written through the moderation service.
        assertThat(count("SELECT count(DISTINCT community_id) FROM community_members WHERE role = 'MODERATOR'")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) FROM reports")).isBetween(8L, 20L);
        assertThat(count("SELECT count(*) FROM reports WHERE status = 'OPEN'")).isZero();
        assertThat(count("SELECT count(*) FROM reports WHERE resolution = 'APPROVED'")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) FROM appeals")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM appeals WHERE status = 'ACCEPTED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM moderation_actions WHERE reversed_at IS NOT NULL")).isEqualTo(1);

        // The leaderboard is computed from those rows and has ranked entries.
        JsonNode board = json.readTree(mvc.perform(get("/api/v1/moderators/leaderboard")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(board.get("entries").size()).isGreaterThanOrEqualTo(3);

        // Two shared (read-only link) collections.
        assertThat(count("SELECT count(*) FROM collections WHERE shared AND share_token IS NOT NULL")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM collection_items")).isEqualTo(6);

        // A demo pool with demo contributions, reconciled in the ledger.
        assertThat(count("SELECT count(*) FROM funding_pools WHERE demo")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM funding_contributions WHERE demo")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM funding_contributions WHERE NOT demo")).isZero();
        assertThat(count("SELECT coalesce(sum(amount_minor), 0) FROM funding_contributions")).isEqualTo(13_000);

        // Idempotent: a second run (e.g. the next restart) adds nothing.
        long reports = count("SELECT count(*) FROM reports");
        assertThat(activity.seedIfNeeded()).isFalse();
        assertThat(count("SELECT count(*) FROM reports")).isEqualTo(reports);

        // Product metrics are exported for Prometheus (in prod only on the management port, D-026).
        String metrics = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("howlers_comments_total").contains("howlers_websocket_sessions")
                .contains("application=\"howlers\"");

        // The UI is told this is demo content.
        JsonNode demo = json.readTree(mvc.perform(get("/api/v1/demo/status")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(demo.get("demoData").asBoolean()).isTrue();
        assertThat(demo.get("message").asText()).contains("not real activity");
    }

    @Autowired PackDemoSeeder packs;

    @Test
    void seedsOnePackRoomAndAFewReactionsOnce() {
        assertThat(count("SELECT count(*) FROM dm_conversations WHERE kind = 'ROOM' AND name = 'Night shift'")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM dm_participants p JOIN dm_conversations c ON c.id = p.conversation_id
                WHERE c.name = 'Night shift'""")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM dm_participants p JOIN dm_conversations c ON c.id = p.conversation_id WHERE c.name = 'Night shift' AND p.role = 'OWNER'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reactions WHERE target_type = 'MESSAGE'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reactions WHERE target_type = 'POST'")).isEqualTo(3);
        long messages = count("SELECT count(*) FROM dm_messages");
        assertThat(packs.seedIfNeeded()).isFalse();
        assertThat(count("SELECT count(*) FROM dm_messages")).isEqualTo(messages);
    }
}
