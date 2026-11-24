package app.howlers.seed;

import app.howlers.common.config.AppProperties;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.messaging.DmService;
import app.howlers.messaging.RoomService;
import app.howlers.reaction.ReactionService;
import app.howlers.reaction.ReactionService.TargetType;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Demo pack room and a few reactions (D-043, D-044), written through the real services as the demo accounts. Runs
 * once: the marker is a room named {@value #ROOM_NAME} that mira_k belongs to. Off with {@code howlers.seed.packs=false}
 * or when the whole demo seed is off.
 */
@Component
@Order(110) // after DemoDataSeeder and DemoActivitySeeder
public class PackDemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PackDemoSeeder.class);
    static final String ROOM_NAME = "Night shift";

    private final JdbcClient jdbc;
    private final RoomService rooms;
    private final DmService dms;
    private final ReactionService reactions;
    private final boolean enabled;

    public PackDemoSeeder(JdbcClient jdbc, RoomService rooms, DmService dms, ReactionService reactions, AppProperties props,
                          @Value("${howlers.seed.packs:true}") boolean packs) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.dms = dms;
        this.reactions = reactions;
        this.enabled = props.seed().enabled() && packs;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        try {
            seedIfNeeded();
        } catch (RuntimeException e) {
            // Demo data is a convenience: never block startup over it.
            log.warn("Demo pack room was not seeded: {}", e.getMessage());
        }
    }

    /** @return true when it seeded now. */
    public boolean seedIfNeeded() {
        Optional<AuthUser> mira = user("mira_k");
        Optional<AuthUser> dev = user("dev_ostrander");
        Optional<AuthUser> nadia = user("nadia_sec");
        Optional<AuthUser> ali = user("rustacean_ali");
        if (mira.isEmpty() || dev.isEmpty() || nadia.isEmpty() || ali.isEmpty()) return false; // base demo data isn't there
        boolean done = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM dm_conversations c JOIN dm_participants p ON p.conversation_id = c.id
                                       WHERE c.kind = 'ROOM' AND c.name = ? AND p.user_id = ?)""")
                .params(ROOM_NAME, mira.get().id()).query(Boolean.class).single();
        if (done) return false;

        long room = rooms.create(mira.get(), ROOM_NAME, "🐺", List.of(dev.get().username(), nadia.get().username())).id();
        long hello = dms.send(room, mira.get(), new SendRequest(
                "Pack room for the release week. Shout here if a deploy looks off.", null)).id();
        long reply = dms.send(room, dev.get(), new SendRequest("On it. Staging is green, I'll watch the error rate tonight.", null)).id();
        rooms.addMembers(room, mira.get(), List.of(ali.get().username()));
        dms.send(room, ali.get(), new SendRequest("Thanks for the add. Happy to review the migration PR.", null));
        reactions.set(nadia.get(), TargetType.MESSAGE, hello, "howl", true);
        reactions.set(mira.get(), TargetType.MESSAGE, reply, "like", true);

        // A couple of reactions on mira_k's community's top post.
        jdbc.sql("""
                        SELECT p.id FROM posts p JOIN communities c ON c.id = p.community_id
                        WHERE c.slug = 'programming' AND p.deleted_at IS NULL AND p.removed = FALSE AND p.pending = FALSE
                        ORDER BY p.score DESC, p.id LIMIT 1""")
                .query(Long.class).optional().ifPresent(postId -> {
                    reactions.set(dev.get(), TargetType.POST, postId, "fire", true);
                    reactions.set(nadia.get(), TargetType.POST, postId, "fire", true);
                    reactions.set(ali.get(), TargetType.POST, postId, "clap", true);
                });
        log.info("Seeded the demo pack room “{}” and a few reactions", ROOM_NAME);
        return true;
    }

    private Optional<AuthUser> user(String username) {
        return jdbc.sql("SELECT id, username, role FROM users WHERE username = ? AND deleted_at IS NULL AND status = 'ACTIVE'")
                .param(username).query((rs, i) -> new AuthUser(rs.getLong(1), rs.getString(2), rs.getString(3))).optional();
    }
}
