package app.howlers.seed;

import app.howlers.common.config.AppProperties;
import app.howlers.vote.ScoreMath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads clearly-labelled demo content from {@code seed/demo.json} on an empty database when
 * {@code howlers.seed.enabled=true}. Idempotent (skips if the marker user exists) and deterministic: votes
 * are assigned to a fixed rotation of demo users, so every run produces the same scores.
 *
 * <p>Demo accounts all share the password in {@link #DEMO_PASSWORD}; it is documented in README.md and
 * must never be enabled in production (the prod profile disables seeding by default).
 */
@Component
@org.springframework.core.annotation.Order(10) // before DemoActivitySeeder, which builds on these rows
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    public static final String DEMO_PASSWORD = "howlers-demo-1";
    private static final String MARKER_USER = "mira_k";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public DemoDataSeeder(JdbcClient jdbc, PasswordEncoder encoder, ObjectMapper mapper, TransactionTemplate tx,
                          AppProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mapper = mapper;
        this.tx = tx;
        this.enabled = props.seed().enabled();
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) return;
        boolean seeded = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(username) = ?)")
                .param(MARKER_USER).query(Boolean.class).single();
        if (seeded) return;
        JsonNode root;
        try (InputStream in = new ClassPathResource("seed/demo.json").getInputStream()) {
            root = mapper.readTree(in);
        }
        tx.executeWithoutResult(s -> seed(root));
        log.info("Seeded demo data (demo accounts use the documented demo password)");
    }

    private final Map<String, Long> userIds = new HashMap<>();
    private final List<Long> rotation = new ArrayList<>();
    private int cursor;

    private void seed(JsonNode root) {
        String hash = encoder.encode(DEMO_PASSWORD);
        Instant base = Instant.now();
        Map<String, Long> topicIds = new HashMap<>();
        jdbc.sql("SELECT slug, id FROM topics").query((rs, i) -> topicIds.put(rs.getString(1), rs.getLong(2))).list();

        int u = 0;
        for (JsonNode n : root.get("users")) {
            String username = n.get("username").asText();
            long id = jdbc.sql("""
                            INSERT INTO users (username, email, password_hash, display_name, bio, email_verified,
                                               onboarding_completed, created_at)
                            VALUES (?, ?, ?, ?, ?, TRUE, TRUE, ?) RETURNING id""")
                    .params(username, username + "@demo.howlers.local", hash, n.get("displayName").asText(),
                            n.get("bio").asText(), ts(base.minus(Duration.ofDays(200 - u * 9L))))
                    .query(Long.class).single();
            userIds.put(username, id);
            rotation.add(id);
            for (JsonNode t : n.get("topics")) {
                Long tid = topicIds.get(t.asText());
                if (tid != null) jdbc.sql("INSERT INTO user_topics (user_id, topic_id) VALUES (?, ?)").params(id, tid).update();
            }
            u++;
        }
        // a few follow edges so the Following feed has content
        follow("first_job_fern", "mira_k");
        follow("first_job_fern", "nadia_sec");
        follow("mira_k", "rustacean_ali");
        follow("quant_priya", "lena_lab");
        follow("dev_ostrander", "mira_k");

        for (JsonNode c : root.get("communities")) seedCommunity(c, topicIds, base);
    }

    private void seedCommunity(JsonNode c, Map<String, Long> topicIds, Instant base) {
        long owner = userIds.get(c.get("owner").asText());
        List<String> tags = new ArrayList<>();
        c.get("tags").forEach(t -> tags.add(t.asText()));
        long cid = jdbc.sql("""
                        INSERT INTO communities (slug, name, description, topic_id, visibility, tags, created_by, created_at)
                        VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?, ?) RETURNING id""")
                .params(c.get("slug").asText(), c.get("name").asText(), c.get("description").asText(),
                        topicIds.get(c.get("topic").asText()), tags.toArray(new String[0]), owner,
                        ts(base.minus(Duration.ofDays(150))))
                .query(Long.class).single();
        int pos = 0;
        for (JsonNode r : c.get("rules")) {
            jdbc.sql("INSERT INTO community_rules (community_id, position, title, description) VALUES (?, ?, ?, ?)")
                    .params(cid, pos++, r.get(0).asText(), r.get(1).asText()).update();
        }
        Map<String, Long> flairs = new HashMap<>();
        for (JsonNode f : c.get("flairs")) {
            long fid = jdbc.sql("INSERT INTO community_flairs (community_id, text, color) VALUES (?, ?, ?) RETURNING id")
                    .params(cid, f.get(0).asText(), f.get(1).asText()).query(Long.class).single();
            flairs.put(f.get(0).asText(), fid);
        }
        join(cid, owner, "OWNER");
        for (JsonNode m : c.get("mods")) join(cid, userIds.get(m.asText()), "MODERATOR");
        // Every demo user joins most communities, with deterministic gaps.
        int i = 0;
        for (long uid : rotation) {
            if ((i++ + cid) % 4 != 0) join(cid, uid, "MEMBER");
        }

        int posts = 0;
        for (JsonNode p : c.get("posts")) {
            long author = userIds.get(p.get("author").asText());
            join(cid, author, "MEMBER");
            Instant created = base.minus(Duration.ofHours(p.get("hoursAgo").asLong()));
            List<String> ptags = new ArrayList<>();
            p.get("tags").forEach(t -> ptags.add(t.asText()));
            String html = p.get("body").asText();
            String text = org.jsoup.Jsoup.parseBodyFragment(html).text();
            long pid = jdbc.sql("""
                            INSERT INTO posts (community_id, author_id, type, title, body_html, body_text, flair_id, tags, created_at)
                            VALUES (?, ?, 'TEXT', ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(cid, author, p.get("title").asText(), html, text, flairs.get(p.get("flair").asText()),
                            ptags.toArray(new String[0]), ts(created))
                    .query(Long.class).single();
            posts++;
            int[] votes = castVotes("post_votes", "post_id", pid, author, p.get("upvotes").asInt(), p.get("downvotes").asInt());
            jdbc.sql("UPDATE users SET post_karma = post_karma + ? WHERE id = ?").params(votes[0] - votes[1], author).update();
            int comments = 0;
            for (JsonNode cm : p.get("comments")) comments += seedComment(cm, pid, null, 0, created.plus(Duration.ofMinutes(20)));
            jdbc.sql("UPDATE posts SET upvotes = ?, downvotes = ?, score = ?, comment_count = ?, hot_rank = ? WHERE id = ?")
                    .params(votes[0], votes[1], votes[0] - votes[1], comments,
                            ScoreMath.hot(votes[0], votes[1], comments, created), pid).update();
        }
        jdbc.sql("UPDATE communities SET post_count = ? WHERE id = ?").params(posts, cid).update();
    }

    /** @return number of comments created in this subtree. */
    private int seedComment(JsonNode n, long postId, Long parentId, int depth, Instant at) {
        long author = userIds.get(n.get("author").asText());
        long id = jdbc.sql("""
                        INSERT INTO comments (post_id, parent_id, author_id, depth, body, created_at)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(postId, parentId, author, depth, n.get("body").asText(), ts(at))
                .query(Long.class).single();
        int[] v = castVotes("comment_votes", "comment_id", id, author, n.path("upvotes").asInt(0), n.path("downvotes").asInt(0));
        jdbc.sql("UPDATE users SET comment_karma = comment_karma + ? WHERE id = ?").params(v[0] - v[1], author).update();
        int count = 1;
        int replies = 0;
        Instant next = at.plus(Duration.ofMinutes(35));
        if (n.has("replies")) {
            for (JsonNode r : n.get("replies")) {
                count += seedComment(r, postId, id, depth + 1, next);
                next = next.plus(Duration.ofMinutes(25));
                replies++;
            }
        }
        jdbc.sql("""
                        UPDATE comments SET upvotes = ?, downvotes = ?, score = ?, best_rank = ?, controversy = ?, reply_count = ?
                        WHERE id = ?""")
                .params(v[0], v[1], v[0] - v[1], ScoreMath.wilson(v[0], v[1]), ScoreMath.controversy(v[0], v[1]), replies, id)
                .update();
        return count;
    }

    /** Real vote rows from distinct demo users (never the author), so counters always match the vote table. */
    private int[] castVotes(String table, String col, long targetId, long author, int up, int down) {
        int ups = 0, downs = 0;
        int attempts = 0;
        while ((ups < up || downs < down) && attempts < rotation.size() * 2) {
            long voter = rotation.get(cursor++ % rotation.size());
            attempts++;
            if (voter == author) continue;
            int value = ups < up ? 1 : -1;
            int n = jdbc.sql("INSERT INTO " + table + " (" + col + ", user_id, value) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(targetId, voter, value).update();
            if (n > 0) {
                if (value == 1) ups++; else downs++;
            }
        }
        return new int[]{ups, downs};
    }

    private void join(long communityId, long userId, String role) {
        int n = jdbc.sql("""
                        INSERT INTO community_members (community_id, user_id, role) VALUES (?, ?, ?)
                        ON CONFLICT (community_id, user_id) DO UPDATE SET role = CASE
                          WHEN community_members.role IN ('OWNER','MODERATOR') THEN community_members.role ELSE EXCLUDED.role END""")
                .params(communityId, userId, role).update();
        jdbc.sql("UPDATE communities SET member_count = (SELECT count(*) FROM community_members WHERE community_id = ?) WHERE id = ?")
                .params(communityId, communityId).update();
    }

    private void follow(String follower, String followee) {
        long a = userIds.get(follower), b = userIds.get(followee);
        jdbc.sql("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)").params(a, b).update();
        jdbc.sql("UPDATE users SET following_count = following_count + 1 WHERE id = ?").param(a).update();
        jdbc.sql("UPDATE users SET follower_count = follower_count + 1 WHERE id = ?").param(b).update();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
