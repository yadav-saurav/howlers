package app.howlers.seed;

import app.howlers.common.config.AppProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Ensures the demo platform admin {@value #USERNAME} exists whenever demo seeding is on, including on databases that
 * were seeded before the admin dashboard existed. Same documented demo password as the other demo accounts; the prod
 * profile disables seeding, so this account never exists in production unless someone enables demo data there.
 */
@Component
public class AdminDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminDemoSeeder.class);
    public static final String USERNAME = "admin_demo";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    public AdminDemoSeeder(JdbcClient jdbc, PasswordEncoder encoder, AppProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.enabled = props.seed().enabled();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(username) = ?)").param(USERNAME)
                .query(Boolean.class).single();
        if (exists) return;
        jdbc.sql("""
                        INSERT INTO users (username, email, password_hash, display_name, bio, role, email_verified,
                                           onboarding_completed, created_at)
                        VALUES (?, ?, ?, 'Demo Admin', 'Platform admin account for the demo. Not a real person.', 'ADMIN',
                                TRUE, TRUE, ?)""")
                .params(USERNAME, USERNAME + "@demo.howlers.local", encoder.encode(DemoDataSeeder.DEMO_PASSWORD),
                        Timestamp.from(Instant.now().minus(Duration.ofDays(300))))
                .update();
        log.info("Created the demo admin account {} (documented demo password)", USERNAME);
    }
}
