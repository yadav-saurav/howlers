package app.howlers.automod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.automod.AutomodService.PreviewResult;
import app.howlers.automod.AutomodService.RuleDto;
import app.howlers.automod.AutomodService.RuleRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import app.howlers.support.TestIds;

/** Per-community automod (D-045): every rule type and action, exemptions, the ReDoS guard, preview and the audit trail. */
class AutomodIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired AutomodService automod;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired JdbcClient jdbc;

    private record World(String slug, AuthUser mod, AuthUser author, long postId) {}

    private AuthUser user(String prefix) {
        String name = prefix + TestIds.suffix();
        var me = auth.register(new RegisterRequest(name, name + "@automod.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private World world() {
        AuthUser mod = user("mod"), author = user("wolf");
        String slug = "am" + TestIds.suffix();
        communities.create(mod, new CreateCommunityRequest(slug, "Automod test", "", null, "PUBLIC", List.of(), List.of()));
        long postId = posts.create(mod, new CreatePostRequest(slug, "TEXT", "Welcome thread", "<p>hi</p>", null, null, null, null, null));
        return new World(slug, mod, author, postId);
    }

    private static RuleRequest rule(String kind, String pattern, Integer threshold, String appliesTo, String action) {
        return new RuleRequest("Rule " + kind.toLowerCase(), kind, pattern, threshold, appliesTo, action, "Reason for " + kind, null);
    }

    private long post(World w, AuthUser u, String title, String html) {
        return posts.create(u, new CreatePostRequest(w.slug(), "TEXT", title, html, null, null, null, null, null));
    }

    private long comment(World w, AuthUser u, String body) {
        return comments.create(w.postId(), u, new CreateCommentRequest(body, null)).id();
    }

    private record State(boolean pending, boolean removed) {}

    private State postState(long id) {
        return jdbc.sql("SELECT pending, removed FROM posts WHERE id = ?").param(id)
                .query((rs, i) -> new State(rs.getBoolean(1), rs.getBoolean(2))).single();
    }

    private State commentState(long id) {
        return jdbc.sql("SELECT pending, removed FROM comments WHERE id = ?").param(id)
                .query((rs, i) -> new State(rs.getBoolean(1), rs.getBoolean(2))).single();
    }

    private List<String> modLog(World w) {
        return jdbc.sql("SELECT action FROM moderation_actions m JOIN communities c ON c.id = m.community_id WHERE c.slug = ? ORDER BY m.id")
                .param(w.slug()).query(String.class).list();
    }

    private List<String> notifications(AuthUser u) {
        return jdbc.sql("SELECT type || ': ' || message FROM notifications WHERE user_id = ? ORDER BY id").param(u.id())
                .query(String.class).list();
    }

    @Test
    void keywordRuleHoldsThroughTheSpamHoldFlowAndTellsTheAuthorWhy() {
        World w = world();
        automod.create(w.slug(), w.mod(), new RuleRequest("Sales", "KEYWORD", "buy now\ncheap*", null, "BOTH", "HOLD",
                "Sales posts are reviewed first", true));

        long held = post(w, w.author(), "Cheapest flights in town", "<p>book today</p>");
        assertThat(postState(held)).isEqualTo(new State(true, true));
        assertThat(jdbc.sql("SELECT rules FROM spam_flags WHERE post_id = ? AND status = 'OPEN'").param(held).query(String.class).single())
                .isEqualTo("AUTOMOD");
        assertThat(modLog(w)).contains("AUTOMOD_RULE", "AUTOMOD_HOLD");
        assertThat(notifications(w.author())).anyMatch(n -> n.startsWith("AUTOMOD_HELD") && n.contains("Sales posts are reviewed first"));

        // The wildcard covers word endings ("cheaply"), and matching ignores case ("BUY NOW").
        long wildcard = post(w, w.author(), "A discussion about pricing", "<p>priced cheaply</p>");
        assertThat(postState(wildcard).pending()).isTrue();
        long fine = post(w, w.author(), "Totally unrelated", "<p>a thoughtful post about wolves</p>");
        assertThat(postState(fine)).isEqualTo(new State(false, false));
        long caps = comment(w, w.author(), "BUY NOW while stocks last");
        assertThat(commentState(caps).pending()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM automod_hits h JOIN communities c ON c.id = h.community_id WHERE c.slug = ?")
                .param(w.slug()).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void keywordsMatchWholeWords() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("KEYWORD", "ass", null, "COMMENTS", "FLAG"));
        long ok = comment(w, w.author(), "A classic passage");
        long hit = comment(w, w.author(), "what an ass");
        List<Long> flagged = jdbc.sql("SELECT comment_id FROM automod_hits WHERE comment_id IN (?, ?)").params(ok, hit).query(Long.class).list();
        assertThat(flagged).containsExactly(hit);
    }

    @Test
    void regexRuleRemovesWithAReasonAndBadPatternsAreRefused() {
        World w = world();
        assertThatThrownBy(() -> automod.create(w.slug(), w.mod(), rule("REGEX", "(a)\\1", null, "BOTH", "REMOVE")))
                .isInstanceOf(ApiException.class).hasMessageContaining("RE2");
        assertThatThrownBy(() -> automod.create(w.slug(), w.mod(), rule("REGEX", "(?<=x)y", null, "BOTH", "REMOVE")))
                .isInstanceOf(ApiException.class);
        automod.create(w.slug(), w.mod(), new RuleRequest("Phone numbers", "REGEX", "\\b\\d{3}-\\d{4}\\b", null, "COMMENTS",
                "REMOVE", "Please don't share phone numbers", true));
        int before = jdbc.sql("SELECT comment_count FROM posts WHERE id = ?").param(w.postId()).query(Integer.class).single();

        long c = comment(w, w.author(), "call me at 555-1234");
        assertThat(commentState(c)).isEqualTo(new State(false, true));
        assertThat(jdbc.sql("SELECT comment_count FROM posts WHERE id = ?").param(w.postId()).query(Integer.class).single()).isEqualTo(before);
        record A(Long moderator, String reason) {}
        A a = jdbc.sql("SELECT moderator_id, reason FROM moderation_actions WHERE comment_id = ? AND action = 'REMOVE_COMMENT'")
                .param(c).query((rs, i) -> new A((Long) rs.getObject(1), rs.getString(2))).single();
        assertThat(a.moderator()).isNull(); // automod, not a person
        assertThat(a.reason()).isEqualTo("Automod: Phone numbers: Please don't share phone numbers");
        assertThat(notifications(w.author())).anyMatch(n -> n.startsWith("CONTENT_REMOVED") && n.contains("Please don't share phone numbers"));
        // Posts aren't covered by a COMMENTS rule.
        assertThat(postState(post(w, w.author(), "My number is 555-1234", "<p>x</p>")).removed()).isFalse();
    }

    @Test
    void linkLimitFlagsWithoutHiding() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("LINK_LIMIT", null, 1, "BOTH", "FLAG"));
        long p = post(w, w.author(), "Two links", "<p><a href=\"https://a.example\">a</a> <a href=\"https://b.example\">b</a></p>");
        assertThat(postState(p)).isEqualTo(new State(false, false));
        assertThat(jdbc.sql("SELECT detail FROM automod_hits WHERE post_id = ? AND action = 'FLAG'").param(p).query(String.class).single())
                .isEqualTo("2 links (limit 1)");
        assertThat(modLog(w)).contains("AUTOMOD_FLAG");
        long one = comment(w, w.author(), "just https://a.example");
        assertThat(jdbc.sql("SELECT count(*) FROM automod_hits WHERE comment_id = ?").param(one).query(Long.class).single()).isZero();
    }

    @Test
    void blockedDomainsIncludeSubdomains() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("DOMAIN", "https://www.Spam.example/\nads.test", null, "BOTH", "HOLD"));
        assertThat(commentState(comment(w, w.author(), "see https://deals.spam.example/x")).pending()).isTrue();
        assertThat(commentState(comment(w, w.author(), "see https://notspam.example/x")).pending()).isFalse();
        long linkPost = posts.create(w.author(), new CreatePostRequest(w.slug(), "LINK", "Great deal", null, "https://ads.test/buy",
                null, null, null, null));
        assertThat(postState(linkPost).pending()).isTrue();
        assertThatThrownBy(() -> automod.create(w.slug(), w.mod(), rule("DOMAIN", "not a domain", null, "BOTH", "HOLD")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void accountAgeAndKarmaMinimums() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("ACCOUNT_AGE", null, 30, "POSTS", "REMOVE"));
        automod.create(w.slug(), w.mod(), rule("KARMA", null, 10, "COMMENTS", "HOLD"));
        assertThat(postState(post(w, w.author(), "Hello from a new wolf", "<p>x</p>"))).isEqualTo(new State(false, true));
        assertThat(commentState(comment(w, w.author(), "first comment")).pending()).isTrue();

        jdbc.sql("UPDATE users SET created_at = now() - interval '60 days', post_karma = 15 WHERE id = ?").param(w.author().id()).update();
        assertThat(postState(post(w, w.author(), "Hello from an older wolf", "<p>x</p>"))).isEqualTo(new State(false, false));
        assertThat(commentState(comment(w, w.author(), "second comment")).pending()).isFalse();
    }

    @Test
    void strongestOutcomeWinsAndDisabledRulesDoNothing() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("KEYWORD", "wolfpack", null, "BOTH", "FLAG"));
        RuleDto remove = automod.create(w.slug(), w.mod(), rule("KEYWORD", "wolf*", null, "BOTH", "REMOVE"));
        long c = comment(w, w.author(), "join the wolfpack");
        assertThat(commentState(c)).isEqualTo(new State(false, true));
        assertThat(jdbc.sql("SELECT action || '>' || outcome FROM automod_hits WHERE comment_id = ? ORDER BY id").param(c)
                .query(String.class).list()).containsExactly("FLAG>REMOVE", "REMOVE>REMOVE");

        automod.update(w.slug(), w.mod(), remove.id(), new RuleRequest(null, null, null, null, null, null, null, false));
        assertThat(commentState(comment(w, w.author(), "the wolfpack again")).removed()).isFalse();
        automod.delete(w.slug(), w.mod(), remove.id());
        assertThat(automod.rules(w.slug(), w.mod())).hasSize(1);
        assertThat(modLog(w).stream().filter("AUTOMOD_RULE"::equals).count()).isEqualTo(4); // 2 created, 1 turned off, 1 deleted
    }

    @Test
    void moderatorsAndAdminsAreExemptAndOnlyModeratorsManageRules() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("KEYWORD", "forbidden", null, "BOTH", "REMOVE"));
        assertThat(postState(post(w, w.mod(), "A forbidden topic, by the mod", "<p>x</p>")).removed()).isFalse();
        AuthUser admin = user("adm");
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(admin.id()).update();
        AuthUser asAdmin = new AuthUser(admin.id(), admin.username(), "ADMIN");
        assertThat(commentState(comment(w, asAdmin, "forbidden words from an admin")).removed()).isFalse();
        // Automod never bans.
        comment(w, w.author(), "forbidden");
        assertThat(jdbc.sql("SELECT count(*) FROM community_bans b JOIN communities c ON c.id = b.community_id WHERE c.slug = ?")
                .param(w.slug()).query(Long.class).single()).isZero();

        assertThatThrownBy(() -> automod.rules(w.slug(), w.author())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> automod.create(w.slug(), w.author(), rule("KEYWORD", "x", null, "BOTH", "FLAG")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> automod.preview(w.slug(), w.author(), rule("KEYWORD", "x", null, "BOTH", "FLAG")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> automod.hits(w.slug(), w.author(), 10)).isInstanceOf(ApiException.class);
    }

    @Test
    void catastrophicPatternsRunInLinearTime() {
        World w = world();
        automod.create(w.slug(), w.mod(), rule("REGEX", "(a+)+$", null, "COMMENTS", "HOLD"));
        automod.create(w.slug(), w.mod(), rule("REGEX", "(x+x+)+y", null, "COMMENTS", "HOLD"));
        String evil = "a".repeat(9_000) + "!" + "x".repeat(900);
        long start = System.nanoTime();
        long c = comment(w, w.author(), evil);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).isLessThan(3_000);
        assertThat(commentState(c).pending()).isFalse();
    }

    @Test
    void previewTestsADraftAgainstRecentContentWithoutChangingAnything() {
        World w = world();
        long p = post(w, w.author(), "Alpha release notes", "<p>see the changelog</p>");
        long c = comment(w, w.author(), "alpha testers wanted");
        comment(w, w.mod(), "alpha from a mod");
        comment(w, w.author(), "nothing to see");

        PreviewResult r = automod.preview(w.slug(), w.mod(), rule("KEYWORD", "alpha", null, "BOTH", "REMOVE"));
        assertThat(r.matchCount()).isEqualTo(3);
        assertThat(r.scannedPosts()).isEqualTo(2);
        assertThat(r.scannedComments()).isEqualTo(3);
        assertThat(r.matches()).anyMatch(m -> m.targetType().equals("POST") && m.postId() == p && !m.authorExempt());
        assertThat(r.matches()).anyMatch(m -> c == (m.commentId() == null ? 0 : m.commentId()) && m.author().id() == w.author().id());
        assertThat(r.matches()).anyMatch(m -> m.authorExempt() && m.author().id() == w.mod().id());
        assertThat(postState(p).removed()).isFalse();
        assertThat(automod.rules(w.slug(), w.mod())).isEmpty();
        assertThat(automod.hits(w.slug(), w.mod(), 50)).isEmpty();

        assertThatThrownBy(() -> automod.preview(w.slug(), w.mod(), rule("REGEX", "(", null, "BOTH", "FLAG")))
                .isInstanceOf(ApiException.class);
        assertThat(automod.preview(w.slug(), w.mod(), rule("ACCOUNT_AGE", null, 7, "COMMENTS", "FLAG")).matchCount()).isEqualTo(3);
    }
}
