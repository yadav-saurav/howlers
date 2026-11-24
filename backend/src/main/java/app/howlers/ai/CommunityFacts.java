package app.howlers.ai;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Facts about the community a Community AI question is scoped to, read from the database: counts, dates,
 * moderators, rules and the latest post titles. Keyword retrieval can't answer questions about the community
 * itself ("how many posts are there?", "what's new here?"), so these go to the model alongside the sources.
 * Only called after the scope's view permission check, and only for that community. Counts include only live
 * content (not deleted or removed).
 */
@Component
public class CommunityFacts {
    static final int MAX_RULES = 10;
    static final int LATEST_POSTS = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;

    public CommunityFacts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Summary(String slug, String name, String description, String visibility, OffsetDateTime createdAt,
                           long members, long posts, long postsLastWeek, OffsetDateTime lastPostAt, long comments) {}

    /** Plain-text facts for the prompt, or {@code null} when the question isn't scoped to a community. */
    public String describe(Long communityId) {
        if (communityId == null) return null;
        Optional<Summary> found = jdbc.sql("""
                        SELECT cm.slug, cm.name, cm.description, cm.visibility, cm.created_at,
                               (SELECT count(*) FROM community_members m WHERE m.community_id = cm.id),
                               (SELECT count(*) FROM posts p
                                 WHERE p.community_id = cm.id AND p.deleted_at IS NULL AND p.removed = FALSE),
                               (SELECT count(*) FROM posts p
                                 WHERE p.community_id = cm.id AND p.deleted_at IS NULL AND p.removed = FALSE
                                   AND p.created_at > now() - interval '7 days'),
                               (SELECT max(p.created_at) FROM posts p
                                 WHERE p.community_id = cm.id AND p.deleted_at IS NULL AND p.removed = FALSE),
                               (SELECT count(*) FROM comments c JOIN posts p ON p.id = c.post_id
                                 WHERE p.community_id = cm.id AND p.deleted_at IS NULL AND p.removed = FALSE
                                   AND c.deleted_at IS NULL AND c.removed = FALSE)
                        FROM communities cm WHERE cm.id = :id""")
                .param("id", communityId)
                .query((rs, i) -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getObject(9, OffsetDateTime.class), rs.getLong(10)))
                .optional();
        if (found.isEmpty()) return null;
        Summary s = found.get();

        List<String> moderators = jdbc.sql("""
                        SELECT u.username, m.role FROM community_members m JOIN users u ON u.id = m.user_id
                        WHERE m.community_id = :id AND m.role IN ('OWNER', 'ADMIN', 'MODERATOR')
                        ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, u.username
                        LIMIT 10""")
                .param("id", communityId)
                .query((rs, i) -> "u/" + rs.getString(1) + " (" + rs.getString(2).toLowerCase() + ")")
                .list();
        List<String> rules = jdbc.sql("SELECT title FROM community_rules WHERE community_id = :id ORDER BY position LIMIT "
                        + MAX_RULES)
                .param("id", communityId)
                .query((rs, i) -> rs.getString(1))
                .list();
        List<String> latest = jdbc.sql("""
                        SELECT p.title, u.username, p.created_at, p.score, p.comment_count
                        FROM posts p LEFT JOIN users u ON u.id = p.author_id
                        WHERE p.community_id = :id AND p.deleted_at IS NULL AND p.removed = FALSE
                        ORDER BY p.created_at DESC LIMIT """ + " " + LATEST_POSTS)
                .param("id", communityId)
                .query((rs, i) -> "- \"" + rs.getString(1) + "\" by " + (rs.getString(2) == null ? "[deleted]" : "u/" + rs.getString(2))
                        + ", " + date(rs.getObject(3, OffsetDateTime.class)) + ", score " + rs.getInt(4)
                        + ", " + plural(rs.getInt(5), "comment"))
                .list();

        StringBuilder b = new StringBuilder();
        b.append("Community: c/").append(s.slug()).append(" (\"").append(s.name()).append("\"), ")
                .append(s.visibility().toLowerCase()).append('\n');
        String description = s.description() == null ? "" : s.description().strip();
        if (!description.isEmpty()) {
            b.append("Description: ").append(description.length() > 400 ? description.substring(0, 399) + "…" : description).append('\n');
        }
        b.append("Created: ").append(date(s.createdAt())).append('\n');
        b.append("Members: ").append(s.members()).append('\n');
        b.append("Posts: ").append(s.posts()).append(" (").append(s.postsLastWeek()).append(" in the last 7 days");
        if (s.lastPostAt() != null) b.append("; latest on ").append(date(s.lastPostAt()));
        b.append(")\n");
        b.append("Comments: ").append(s.comments()).append('\n');
        if (!moderators.isEmpty()) b.append("Moderators: ").append(String.join(", ", moderators)).append('\n');
        if (!rules.isEmpty()) {
            b.append("Rules:\n");
            for (int i = 0; i < rules.size(); i++) b.append(i + 1).append(". ").append(rules.get(i)).append('\n');
        }
        if (!latest.isEmpty()) b.append("Latest posts:\n").append(String.join("\n", latest)).append('\n');
        return b.toString().strip();
    }

    private static String date(OffsetDateTime t) {
        return t == null ? "unknown" : DATE.format(t);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
