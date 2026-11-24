package app.howlers.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityRole;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.moderation.ModerationService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.recognition.RecognitionDtos.ModeratorScore;
import app.howlers.recognition.RecognitionService;
import app.howlers.support.IntegrationTest;
import app.howlers.vote.VoteService;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReputationAndRecognitionIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired VoteService votes;
    @Autowired ModerationService mod;
    @Autowired ReputationService reputation;
    @Autowired RecognitionService recognition;
    @Autowired JdbcClient jdbc;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** 8–10 lowercase alphanumerics, unique within a run. */
    private static String uniq() {
        String t = Long.toString(System.nanoTime(), 36);
        return t.substring(Math.max(0, t.length() - 7)) + SEQ.incrementAndGet();
    }

    private AuthUser user(String prefix) {
        String name = prefix + uniq();
        var me = auth.register(new RegisterRequest(name, name + "@r.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    /** An established account: old enough and with enough karma that its votes count in full. */
    private AuthUser veteran(String prefix) {
        AuthUser u = user(prefix);
        jdbc.sql("UPDATE users SET created_at = now() - interval '90 days', post_karma = 100 WHERE id = ?").param(u.id()).update();
        return u;
    }

    private long topic(String slug) {
        return jdbc.sql("SELECT id FROM topics WHERE slug = ?").param(slug).query(Long.class).single();
    }

    private String community(AuthUser owner, String topicSlug) {
        String slug = "rep" + uniq();
        communities.create(owner, new CreateCommunityRequest(slug, "Rep test", "", topic(topicSlug), "PUBLIC", List.of(), List.of()));
        return slug;
    }

    private long post(AuthUser author, String slug, List<String> tags) {
        return posts.create(author, new CreatePostRequest(slug, "TEXT", "Post " + uniq(), "<p>body</p>", null, null, null, tags, null));
    }

    private double points(AuthUser u, String topicSlug) {
        return jdbc.sql("SELECT coalesce(max(points), 0) FROM topic_reputation WHERE user_id = ? AND topic_id = ?")
                .params(u.id(), topic(topicSlug)).query(Double.class).single();
    }

    private void drain() {
        while (reputation.processDirty(1000).authors() > 0) {
            // keep going until the queue is empty
        }
    }

    @Test
    void votesBecomeWeightedTopicReputationAndRemovalReversesThem() {
        AuthUser owner = veteran("ow"), author = veteran("au"), v1 = veteran("va"), v3 = veteran("vc");
        AuthUser fresh = user("nw"); // a brand-new account
        String slug = community(owner, "gaming");
        for (AuthUser u : List.of(author, v1, v3, fresh)) communities.join(slug, u);
        long p = post(author, slug, List.of("science")); // topics: gaming (community) + science (tag)

        votes.votePost(p, v1, 1);
        votes.votePost(p, author, 1); // self-vote: shown, never counted
        votes.votePost(p, fresh, 1);  // young account: counts a quarter
        votes.votePost(p, v3, -1);    // downvote: subtracts half
        // The vote itself only queued the author; the job does the math.
        assertThat(jdbc.sql("SELECT count(*) FROM reputation_dirty WHERE user_id = ?").param(author.id()).query(Long.class).single())
                .isLessThanOrEqualTo(1L);
        drain();
        double expected = 1.0 + 0.25 - 0.5;
        assertThat(points(author, "gaming")).isCloseTo(expected, within(1e-9));
        assertThat(points(author, "science")).isCloseTo(expected, within(1e-9));
        assertThat(points(v1, "gaming")).isZero();

        // A moderator removes the post: its votes stop counting. Approving it restores them.
        mod.postAction(p, owner, Action.REMOVE_POST, "Off topic");
        drain();
        assertThat(points(author, "gaming")).isZero();
        mod.postAction(p, owner, Action.APPROVE_POST, null);
        drain();
        assertThat(points(author, "gaming")).isCloseTo(expected, within(1e-9));

        // Surfaces: leaderboard and profile read the same totals.
        assertThat(reputation.forUser(author.username(), null).topics()).isEmpty(); // < 1 point isn't listed
    }

    @Test
    void pairCapStopsOneVoterFarmingAnAuthor() {
        AuthUser owner = veteran("ow"), author = veteran("au"), voter = veteran("vf");
        String slug = community(owner, "fitness");
        communities.join(slug, author);
        communities.join(slug, voter);
        long p = post(owner, slug, List.of());
        for (int i = 0; i < 5; i++) {
            long c = comments.create(p, author, new CreateCommentRequest("Comment number " + i + " " + uniq(), null)).id();
            votes.voteComment(c, voter, 1);
        }
        drain();
        assertThat(points(author, "fitness")).isEqualTo((double) ReputationMath.PAIR_DAILY_CAP);
        int capped = jdbc.sql("SELECT votes_capped FROM topic_reputation WHERE user_id = ? AND topic_id = ?")
                .params(author.id(), topic("fitness")).query(Integer.class).single();
        assertThat(capped).isEqualTo(2);

        var board = reputation.leaders("fitness", 100);
        assertThat(board.entries()).anySatisfy(e -> {
            assertThat(e.user().id()).isEqualTo(author.id());
            assertThat(e.points()).isEqualTo(3.0);
        });
        var mine = reputation.forUser(author.username(), null).topics();
        assertThat(mine).singleElement().satisfies(t -> {
            assertThat(t.topic().slug()).isEqualTo("fitness");
            assertThat(t.rank()).isPositive();
        });
    }

    @Test
    void rebuildIsDeterministicAndAppliesQualityWeights() {
        AuthUser owner = veteran("ow"), expert = veteran("ex"), author = veteran("au"), v1 = veteran("v1"), v2 = veteran("v2");
        String slug = community(owner, "design");
        for (AuthUser u : List.of(expert, author, v1, v2)) communities.join(slug, u);
        long ep = post(expert, slug, List.of());
        votes.votePost(ep, v1, 1);
        votes.votePost(ep, v2, 1); // expert: base 2 in design
        long ap = post(author, slug, List.of());
        votes.votePost(ap, expert, 1);

        reputation.rebuildAll();
        double weight = ReputationMath.quality(2.0);
        assertThat(weight).isGreaterThan(1.0);
        assertThat(points(author, "design")).isCloseTo(weight, within(1e-9));
        assertThat(points(expert, "design")).isCloseTo(2.0, within(1e-9));

        // Determinism: rebuilding again from the same rows gives exactly the same table.
        Map<String, Double> first = snapshot();
        reputation.rebuildAll();
        assertThat(snapshot()).isEqualTo(first);

        // The incremental path (vote → queue → job) lands on the same numbers a full rebuild would.
        votes.votePost(ap, v1, 1);
        drain();
        Map<String, Double> incremental = snapshot();
        assertThat(points(author, "design")).isCloseTo(weight + 1.0, within(1e-9));
        reputation.rebuildAll();
        assertThat(snapshot()).isEqualTo(incremental);
    }

    private Map<String, Double> snapshot() {
        Map<String, Double> m = new TreeMap<>();
        jdbc.sql("SELECT user_id, topic_id, points, base_points FROM topic_reputation")
                .query((rs, i) -> m.put(rs.getLong(1) + ":" + rs.getLong(2), rs.getDouble(3) * 1e6 + rs.getDouble(4))).list();
        return m;
    }

    // ------------------------------------------------------------------ moderator recognition

    private ModeratorScore scoreOf(AuthUser m, String slug) {
        long cid = jdbc.sql("SELECT id FROM communities WHERE slug = ?").param(slug).query(Long.class).single();
        recognition.recompute(RecognitionService.currentPeriod(), cid);
        return recognition.record(m.username(), m).entries().stream()
                .filter(e -> e.community().slug().equals(slug) && e.period().equals(YearMonth.now(java.time.ZoneOffset.UTC).toString()))
                .findFirst().orElseThrow();
    }

    @Test
    void appealOverturnLowersConsistencyAndOptOutHidesFromLeaderboard() {
        AuthUser owner = veteran("ow"), m1 = veteran("m1"), m2 = veteran("m2"), member = veteran("me"), reporter = veteran("rp");
        String slug = community(owner, "books");
        for (AuthUser u : List.of(m1, m2, member, reporter)) communities.join(slug, u);
        communities.setRole(slug, owner, m1.username(), CommunityRole.MODERATOR);
        communities.setRole(slug, owner, m2.username(), CommunityRole.MODERATOR);

        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = post(member, slug, List.of());
            mod.report(reporter, new ReportRequest("POST", ids[i], null, "Spam", null));
            mod.postAction(ids[i], m1, Action.REMOVE_POST, "Spam");
        }
        ModeratorScore before = scoreOf(m1, slug);
        assertThat(before.inputs().decisions()).isEqualTo(3);
        assertThat(before.inputs().reportsHandled()).isEqualTo(3);
        assertThat(before.components().consistency()).isEqualTo(1.0);
        assertThat(before.ranked()).isTrue();

        // The member appeals one removal and the other moderator overturns it.
        long actionId = jdbc.sql("SELECT id FROM moderation_actions WHERE post_id = ? AND action = 'REMOVE_POST'").param(ids[0])
                .query(Long.class).single();
        var appeal = mod.submitAppeal(actionId, member, "This was a genuine question, not spam at all.");
        mod.resolveAppeal(appeal.id(), m2, true, "Agreed");
        ModeratorScore after = scoreOf(m1, slug);
        assertThat(after.inputs().decisionsReversed()).isEqualTo(1);
        assertThat(after.components().consistency()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(after.components().appealQuality()).isZero();
        assertThat(after.score()).isLessThan(before.score());

        // Peer endorsement: co-moderators only, never yourself, one per period (re-endorsing just edits the note).
        assertThatThrownBy(() -> recognition.endorse(slug, m1, m1.username(), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> recognition.endorse(slug, member, m1.username(), null)).isInstanceOf(ApiException.class);
        recognition.endorse(slug, m2, m1.username(), "Quick and fair");
        recognition.endorse(slug, m2, m1.username(), "Quick and fair, again");
        ModeratorScore endorsed = scoreOf(m1, slug);
        assertThat(endorsed.inputs().endorsements()).isEqualTo(1);
        assertThat(endorsed.components().peer()).isPositive();
        assertThat(recognition.peers(slug, m2).peers()).anySatisfy(p -> {
            assertThat(p.moderator().id()).isEqualTo(m1.id());
            assertThat(p.endorsedByMe()).isTrue();
            assertThat(p.note()).isEqualTo("Quick and fair, again");
        });

        var board = recognition.leaderboard(null, slug, 50, null).entries();
        assertThat(board).anySatisfy(e -> assertThat(e.moderator().id()).isEqualTo(m1.id()));
        assertThat(recognition.leaderboard(null, null, 100, null).entries()).anySatisfy(e -> assertThat(e.moderator().id()).isEqualTo(m1.id()));

        // Opting out removes them from every public ranking and hides the profile record from others.
        recognition.updateSettings(m1, false);
        assertThat(recognition.leaderboard(null, slug, 50, null).entries()).noneMatch(e -> e.moderator().id() == m1.id());
        assertThat(recognition.leaderboard(null, null, 100, null).entries()).noneMatch(e -> e.moderator().id() == m1.id());
        assertThat(recognition.record(m1.username(), member).hidden()).isTrue();
        var own = recognition.record(m1.username(), m1);
        assertThat(own.hidden()).isFalse();
        assertThat(own.optedOut()).isTrue();
        assertThat(own.entries()).allSatisfy(e -> assertThat(e.ranked()).isFalse());
        recognition.updateSettings(m1, true);
        assertThat(recognition.leaderboard(null, slug, 50, null).entries()).anySatisfy(e -> assertThat(e.moderator().id()).isEqualTo(m1.id()));
    }
}
