package app.howlers.spam;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Write-time spam heuristics (D-021). Each check is a handful of indexed lookups on the author's own recent rows, so
 * it costs about as much as the duplicate-title guard that already runs on every post. Moderators of the community
 * and platform admins are exempt. Content that trips a rule is held for review; content far past a threshold is
 * refused, and the refusal is still recorded as a flag so admins can see what was blocked.
 */
@Component
public class SpamGuard {
    public static final String LINK_VELOCITY = "LINK_VELOCITY";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String BURST = "BURST";
    public static final String MENTIONS = "MENTIONS";

    private final SpamProperties props;
    private final JdbcClient jdbc;
    private final SpamFlags flags;

    public SpamGuard(SpamProperties props, JdbcClient jdbc, SpamFlags flags) {
        this.props = props;
        this.jdbc = jdbc;
        this.flags = flags;
    }

    /** Empty rules = clean. */
    public record Verdict(List<String> rules, String details) {
        public static final Verdict CLEAN = new Verdict(List.of(), "");

        public boolean held() {
            return !rules.isEmpty();
        }
    }

    public record PostInput(long communityId, String title, String bodyText, String contentHash, String linkHash,
                            int linkCount) {}

    private static final class Tally {
        final List<String> hold = new ArrayList<>();
        final List<String> reject = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        void hit(String rule, boolean rejectIt, String detail) {
            (rejectIt ? reject : hold).add(rule);
            details.add(detail);
        }
    }

    public Verdict checkPost(AuthUser user, boolean exempt, PostInput in) {
        if (!props.isEnabled() || exempt || user.isAdmin()) return Verdict.CLEAN;
        Tally t = new Tally();
        boolean isNew = isNewAccount(user.id());
        mentions(t, in.title() + "\n" + in.bodyText());
        if (isNew) {
            linkVelocity(t, user.id(), in.linkCount());
            burst(t, user.id());
        }
        duplicate(t, user.id(), in);
        String excerpt = clip(in.title() + (in.bodyText().isBlank() ? "" : " — " + in.bodyText()), 300);
        return finish(t, user, in.communityId(), "POST", excerpt);
    }

    public Verdict checkComment(AuthUser user, boolean exempt, long communityId, String body, int linkCount) {
        if (!props.isEnabled() || exempt || user.isAdmin()) return Verdict.CLEAN;
        Tally t = new Tally();
        mentions(t, body);
        if (isNewAccount(user.id())) {
            linkVelocity(t, user.id(), linkCount);
            burst(t, user.id());
        }
        return finish(t, user, communityId, "COMMENT", clip(body, 300));
    }

    private Verdict finish(Tally t, AuthUser user, long communityId, String type, String excerpt) {
        String details = String.join("; ", t.details);
        if (!t.reject.isEmpty()) {
            List<String> all = new ArrayList<>(t.reject);
            t.hold.stream().filter(r -> !all.contains(r)).forEach(all::add);
            flags.recordRejected(communityId, user.id(), type, String.join(",", all), clip(details, 500), excerpt);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SPAM_REJECTED",
                    "This looks like spam, so it wasn't posted. If that's a mistake, wait a while and try again, or contact the moderators.");
        }
        if (t.hold.isEmpty()) return Verdict.CLEAN;
        return new Verdict(List.copyOf(t.hold), clip(details, 500));
    }

    // ------------------------------------------------------------------ rules

    private void mentions(Tally t, String text) {
        SpamProperties.Mentions m = props.getMentions();
        if (!m.isEnabled()) return;
        int n = SpamSignals.mentionCount(text);
        if (n > m.getReject()) t.hit(MENTIONS, true, n + " mentions (limit " + m.getMax() + ")");
        else if (n > m.getMax()) t.hit(MENTIONS, false, n + " mentions (limit " + m.getMax() + ")");
    }

    private void linkVelocity(Tally t, long userId, int linkCount) {
        SpamProperties.LinkVelocity lv = props.getLinkVelocity();
        if (!lv.isEnabled() || linkCount == 0) return;
        long secs = lv.getWindow().toSeconds();
        long recent = jdbc.sql("""
                        SELECT (SELECT coalesce(sum(link_count), 0) FROM posts
                                WHERE author_id = :u AND created_at > now() - :s * interval '1 second')
                             + (SELECT coalesce(sum(link_count), 0) FROM comments
                                WHERE author_id = :u AND created_at > now() - :s * interval '1 second')""")
                .param("u", userId).param("s", secs).query(Long.class).single();
        long total = recent + linkCount;
        String detail = total + " links in " + minutes(lv.getWindow()) + " from a new account (limit " + lv.getMaxLinks() + ")";
        if (total >= lv.getRejectLinks()) t.hit(LINK_VELOCITY, true, detail);
        else if (total > lv.getMaxLinks()) t.hit(LINK_VELOCITY, false, detail);
    }

    private void burst(Tally t, long userId) {
        SpamProperties.Burst b = props.getBurst();
        if (!b.isEnabled()) return;
        long secs = b.getWindow().toSeconds();
        long recent = jdbc.sql("""
                        SELECT (SELECT count(*) FROM posts WHERE author_id = :u AND created_at > now() - :s * interval '1 second')
                             + (SELECT count(*) FROM comments WHERE author_id = :u AND created_at > now() - :s * interval '1 second')""")
                .param("u", userId).param("s", secs).query(Long.class).single();
        long total = recent + 1;
        String detail = total + " posts and comments in " + minutes(b.getWindow()) + " from a new account (limit " + b.getMaxItems() + ")";
        if (total > b.getRejectItems()) t.hit(BURST, true, detail);
        else if (total > b.getMaxItems()) t.hit(BURST, false, detail);
    }

    private void duplicate(Tally t, long userId, PostInput in) {
        SpamProperties.Duplicate d = props.getDuplicate();
        if (!d.isEnabled()) return;
        boolean crossAccount = SpamSignals.normalize(in.bodyText()).length() >= d.getCrossAccountMinBody();
        long others = jdbc.sql("""
                        SELECT count(DISTINCT community_id) FROM posts
                        WHERE created_at > now() - :s * interval '1 second' AND deleted_at IS NULL AND community_id <> :c
                          AND ((author_id = :u AND (content_hash = :h OR (CAST(:lh AS varchar) IS NOT NULL AND link_hash = :lh)))
                               OR (:cross AND content_hash = :h))""")
                .param("s", d.getWindow().toSeconds()).param("c", in.communityId()).param("u", userId)
                .param("h", in.contentHash()).param("lh", in.linkHash()).param("cross", crossAccount)
                .query(Long.class).single();
        long communities = others + 1;
        String detail = "same content in " + communities + " communities within " + minutes(d.getWindow());
        if (communities >= d.getRejectCommunities()) t.hit(DUPLICATE, true, detail);
        else if (communities >= d.getMaxCommunities()) t.hit(DUPLICATE, false, detail);
    }

    private boolean isNewAccount(long userId) {
        OffsetDateTime created = jdbc.sql("SELECT created_at FROM users WHERE id = ?").param(userId)
                .query(OffsetDateTime.class).optional().orElse(null);
        return created != null && created.toInstant().isAfter(Instant.now().minus(props.getNewAccountAge()));
    }

    private static String minutes(Duration d) {
        long m = d.toMinutes();
        return m >= 60 && m % 60 == 0 ? (m / 60) + " h" : m + " min";
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
