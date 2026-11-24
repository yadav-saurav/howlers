package app.howlers.seed;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the UI whether this database contains the fictional demo data, so it can say so instead of letting seeded
 * activity pass as real. Based on the rows actually present (demo accounts use the reserved {@code demo.howlers.local}
 * email domain), not on the current config, so turning the seed flag off later doesn't hide existing demo content.
 */
@Tag(name = "Meta")
@RestController
public class DemoStatusController {
    private final JdbcClient jdbc;

    public DemoStatusController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DemoStatus(boolean demoData, String message) {}

    @GetMapping("/api/v1/demo/status")
    public DemoStatus status() {
        boolean present = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE email LIKE '%@demo.howlers.local' AND deleted_at IS NULL)")
                .query(Boolean.class).single();
        return new DemoStatus(present, present
                ? "This instance contains demo data: the demo accounts, posts, votes, moderation history, collections and funding are "
                  + "fictional sample content, not real activity. Demo funding never involves real money."
                : null);
    }
}
