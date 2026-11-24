package app.howlers.spam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CommentNode;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityRole;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.feed.FeedService;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationService;
import app.howlers.mute.MuteService;
import app.howlers.notification.NotificationService;
import app.howlers.onboarding.OnboardingController;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Mutes, spam heuristics (each one switched on here), the hold/approve flow, onboarding skip and feed mod rights. */
class TrustAndSafetyIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired FeedService feeds;
    @Autowired MuteService mutes;
    @Autowired NotificationService notifications;
    @Autowired SpamProperties spamProps;
    @Autowired SpamService spam;
    @Autowired ModerationService mod;
    @Autowired OnboardingController onboarding;
    @Autowired JdbcClient jdbc;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@ts.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private static String sfx() {
        return Long.toString(System.nanoTime() % 1_000_000);
    }

    private record World(AuthUser owner, AuthUser moderator, AuthUser alice, AuthUser bob, String slug) {}

    private World world() {
        String s = sfx();
        AuthUser owner = user("to" + s), moderator = user("tm" + s), alice = user("ta" + s), bob = user("tb" + s);
        String slug = "ts" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Trust test", "", null, "PUBLIC", List.of(), List.of()));
        for (AuthUser u : List.of(moderator, alice, bob)) communities.join(slug, u);
        communities.setRole(slug, owner, moderator.username(), CommunityRole.MODERATOR);
        return new World(owner, moderator, alice, bob, slug);
    }

    private long post(AuthUser u, String slug, String title, String body) {
        return posts.create(u, new CreatePostRequest(slug, "TEXT", title, body, null, null, null, null, null));
    }

    private List<Long> ids(List<PostSummary> items) {
        return items.stream().map(PostSummary::id).toList();
    }

    /** Spam settings are shared by the whole context: every test that changes them gets them reset here. */
    @AfterEach
    void resetSpam() {
        SpamProperties fresh = new SpamProperties();
        spamProps.setEnabled(false);
        spamProps.setNewAccountAge(fresh.getNewAccountAge());
        spamProps.setLinkVelocity(fresh.getLinkVelocity());
        spamProps.setDuplicate(fresh.getDuplicate());
        spamProps.setBurst(fresh.getBurst());
        spamProps.setMentions(fresh.getMentions());
    }

    /** Turns the heuristics on with only {@code rule} active. */
    private void only(String rule) {
        spamProps.setEnabled(true);
        spamProps.getLinkVelocity().setEnabled(rule.equals(SpamGuard.LINK_VELOCITY));
        spamProps.getDuplicate().setEnabled(rule.equals(SpamGuard.DUPLICATE));
        spamProps.getBurst().setEnabled(rule.equals(SpamGuard.BURST));
        spamProps.getMentions().setEnabled(rule.equals(SpamGuard.MENTIONS));
    }

    // ------------------------------------------------------------------ mutes

    @Test
    void mutedUserIsHiddenFromFeedsCollapsedInThreadsAndCannotNotify() {
        World w = world();
        long alicePost = post(w.alice(), w.slug(), "Alice's take", "<p>hello</p>");
        long bobPost = post(w.bob(), w.slug(), "Bob's take", "<p>hi</p>");
        mutes.muteUser(w.owner(), w.alice().username(), true);

        assertThat(ids(feeds.home("latest", null, null, 50, w.owner()).items())).contains(bobPost).doesNotContain(alicePost);
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.owner()).items())).contains(bobPost).doesNotContain(alicePost);
        // mutes are private and one-directional: others still see Alice
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.bob()).items())).contains(alicePost);

        long ownerPost = post(w.owner(), w.slug(), "Owner's thread", "<p>discuss</p>");
        long before = notifications.unreadCount(w.owner().id());
        CommentNode fromAlice = comments.create(ownerPost, w.alice(), new CreateCommentRequest("I disagree", null));
        comments.create(ownerPost, w.bob(), new CreateCommentRequest("Agreed", null));
        assertThat(notifications.unreadCount(w.owner().id())).isEqualTo(before + 1); // only Bob's reply notified

        List<CommentNode> thread = comments.topLevel(ownerPost, w.owner(), "new", null, 20).items();
        CommentNode seen = thread.stream().filter(c -> c.id() == fromAlice.id()).findFirst().orElseThrow();
        assertThat(seen.muted()).isTrue();
        assertThat(seen.body()).isEqualTo("I disagree"); // collapsed, not erased: the reader can expand it
        assertThat(comments.topLevel(ownerPost, w.bob(), "new", null, 20).items())
                .filteredOn(c -> c.id() == fromAlice.id()).allMatch(c -> !c.muted());

        assertThat(mutes.list(w.owner()).users()).extracting(u -> u.username()).containsExactly(w.alice().username());
        mutes.muteUser(w.owner(), w.alice().username(), false);
        assertThat(ids(feeds.home("latest", null, null, 50, w.owner()).items())).contains(alicePost);
        assertThatThrownBy(() -> mutes.muteUser(w.owner(), w.owner().username(), true)).isInstanceOf(ApiException.class);
    }

    @Test
    void mutedCommunityLeavesHomeFeedsButItsPageStillWorks() {
        World w = world();
        long p = post(w.alice(), w.slug(), "Community post", "<p>x</p>");
        mutes.muteCommunity(w.bob(), w.slug(), true);
        assertThat(ids(feeds.home("latest", null, null, 50, w.bob()).items())).doesNotContain(p);
        assertThat(ids(feeds.home("foryou", null, null, 50, w.bob()).items())).doesNotContain(p);
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.bob()).items())).contains(p);
        assertThat(mutes.list(w.bob()).communities()).extracting(c -> c.slug()).containsExactly(w.slug());
    }

    // ------------------------------------------------------------------ spam heuristics

    @Test
    void linkVelocityHoldsANewAccountsLinkBurst() {
        World w = world();
        only(SpamGuard.LINK_VELOCITY);
        spamProps.getLinkVelocity().setMaxLinks(2);
        long p = post(w.owner(), w.slug(), "Links welcome", "");
        CommentNode first = comments.create(p, w.alice(), new CreateCommentRequest("see https://a.example and https://b.example", null));
        assertThat(first.pending()).isFalse();
        CommentNode second = comments.create(p, w.alice(), new CreateCommentRequest("also https://c.example", null));
        assertThat(second.pending()).isTrue();
        assertThat(spam.communityHolds(w.slug(), w.moderator())).anySatisfy(f -> {
            assertThat(f.commentId()).isEqualTo(second.id());
            assertThat(f.rules()).containsExactly(SpamGuard.LINK_VELOCITY);
            assertThat(f.outcome()).isEqualTo("HELD");
        });
        // established accounts are not subject to the new-account rules
        spamProps.setNewAccountAge(Duration.ZERO);
        assertThat(comments.create(p, w.bob(), new CreateCommentRequest("https://d.example https://e.example https://f.example", null)).pending()).isFalse();
    }

    @Test
    void duplicateContentAcrossCommunitiesIsHeld() {
        World a = world();
        World b = world();
        communities.join(b.slug(), a.alice());
        only(SpamGuard.DUPLICATE);
        spamProps.getDuplicate().setMaxCommunities(2);
        long first = post(a.alice(), a.slug(), "Amazing deal!!", "<p>Cheap watches here</p>");
        long second = post(a.alice(), b.slug(), "amazing deal", "<p>cheap   watches HERE.</p>");
        assertThat(posts.detail(first, a.alice()).pending()).isFalse();
        assertThat(posts.detail(second, a.alice()).pending()).isTrue();
        assertThat(spam.communityHolds(b.slug(), b.moderator())).anySatisfy(f -> {
            assertThat(f.postId()).isEqualTo(second);
            assertThat(f.rules()).containsExactly(SpamGuard.DUPLICATE);
        });
    }

    @Test
    void newAccountBurstIsHeld() {
        World w = world();
        only(SpamGuard.BURST);
        spamProps.getBurst().setMaxItems(3);
        long p = post(w.owner(), w.slug(), "Burst thread", "");
        for (int i = 0; i < 3; i++) {
            assertThat(comments.create(p, w.bob(), new CreateCommentRequest("comment " + i, null)).pending()).isFalse();
        }
        assertThat(comments.create(p, w.bob(), new CreateCommentRequest("comment 4", null)).pending()).isTrue();
        // moderators of the community are exempt
        for (int i = 0; i < 5; i++) {
            assertThat(comments.create(p, w.moderator(), new CreateCommentRequest("mod note " + i, null)).pending()).isFalse();
        }
    }

    @Test
    void excessiveMentionsAreHeldAndClearAbuseIsRejectedButRecorded() {
        World w = world();
        only(SpamGuard.MENTIONS);
        spamProps.getMentions().setMax(2);
        spamProps.getMentions().setReject(4);
        long p = post(w.owner(), w.slug(), "Mentions", "");
        assertThat(comments.create(p, w.alice(), new CreateCommentRequest("@ann @bob", null)).pending()).isFalse();
        assertThat(comments.create(p, w.alice(), new CreateCommentRequest("@ann @bob @cat", null)).pending()).isTrue();

        assertThatThrownBy(() -> comments.create(p, w.alice(), new CreateCommentRequest("@a1x @b1x @c1x @d1x @e1x", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("spam");
        Long rejected = jdbc.sql("""
                        SELECT count(*) FROM spam_flags WHERE author_id = ? AND outcome = 'REJECTED' AND status = 'CLOSED'
                        AND rules LIKE '%MENTIONS%' AND comment_id IS NULL""")
                .param(w.alice().id()).query(Long.class).single();
        assertThat(rejected).isEqualTo(1); // recorded although the comment itself rolled back
        assertThat(jdbc.sql("SELECT count(*) FROM comments WHERE body LIKE '@a1x%'").query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ hold / approve / remove

    @Test
    void heldContentIsVisibleOnlyToAuthorAndModeratorsUntilApproved() {
        World w = world();
        long ownerPost = post(w.owner(), w.slug(), "Open thread", "");
        only(SpamGuard.MENTIONS);
        spamProps.getMentions().setMax(1);
        long held = post(w.alice(), w.slug(), "Hello @ann and @bob", "");
        CommentNode heldComment = comments.create(ownerPost, w.alice(), new CreateCommentRequest("cc @ann @bob", null));
        spamProps.setEnabled(false);

        // hidden from others
        assertThatThrownBy(() -> posts.detail(held, w.bob())).isInstanceOf(ApiException.class).hasMessageContaining("not found");
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.bob()).items())).doesNotContain(held);
        assertThat(comments.topLevel(ownerPost, w.bob(), "new", null, 20).items()).noneMatch(c -> c.id() == heldComment.id());
        assertThatThrownBy(() -> comments.one(heldComment.id(), w.bob())).isInstanceOf(ApiException.class);
        assertThat(posts.detail(ownerPost, w.owner()).commentCount()).isZero();
        long ownerUnread = notifications.unreadCount(w.owner().id());

        // visible to the author and moderators, marked pending
        assertThat(posts.detail(held, w.alice()).pending()).isTrue();
        assertThat(posts.detail(held, w.alice()).title()).isEqualTo("Hello @ann and @bob");
        assertThat(posts.detail(held, w.moderator()).pending()).isTrue();
        assertThat(comments.topLevel(ownerPost, w.alice(), "new", null, 20).items())
                .anySatisfy(c -> { assertThat(c.id()).isEqualTo(heldComment.id()); assertThat(c.pending()).isTrue(); assertThat(c.body()).isEqualTo("cc @ann @bob"); });
        assertThat(comments.topLevel(ownerPost, w.moderator(), "new", null, 20).items()).anyMatch(c -> c.id() == heldComment.id());
        assertThatThrownBy(() -> spam.communityHolds(w.slug(), w.bob())).isInstanceOf(ApiException.class);
        assertThat(mod.stats(w.slug(), w.moderator()).heldForReview()).isEqualTo(2);

        // approve both: the comment through the flag, the post through the regular approve action
        var commentFlag = spam.communityHolds(w.slug(), w.moderator()).stream().filter(f -> f.commentId() != null).findFirst().orElseThrow();
        assertThatThrownBy(() -> spam.resolve(commentFlag.id(), w.bob(), true)).isInstanceOf(ApiException.class);
        spam.resolve(commentFlag.id(), w.moderator(), true);
        mod.postAction(held, w.moderator(), Action.APPROVE_POST, null);

        assertThat(posts.detail(held, w.bob()).pending()).isFalse();
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.bob()).items())).contains(held);
        assertThat(comments.topLevel(ownerPost, w.bob(), "new", null, 20).items()).anyMatch(c -> c.id() == heldComment.id() && !c.pending());
        assertThat(posts.detail(ownerPost, w.owner()).commentCount()).isEqualTo(1);
        assertThat(notifications.unreadCount(w.owner().id())).isEqualTo(ownerUnread + 1); // reply notification on release
        assertThat(spam.communityHolds(w.slug(), w.moderator())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM moderation_actions WHERE comment_id = ? AND action = 'RELEASE_HELD'")
                .param(heldComment.id()).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> spam.resolve(commentFlag.id(), w.moderator(), true)).hasMessageContaining("already resolved");
    }

    @Test
    void removingHeldContentKeepsItHiddenAndTellsTheAuthor() {
        World w = world();
        only(SpamGuard.MENTIONS);
        spamProps.getMentions().setMax(1);
        long held = post(w.alice(), w.slug(), "Ping @ann @bob", "");
        spamProps.setEnabled(false);
        var flag = spam.communityHolds(w.slug(), w.moderator()).getFirst();
        spam.resolve(flag.id(), w.moderator(), false);
        assertThat(ids(feeds.community(w.slug(), "new", null, null, 50, w.bob()).items())).doesNotContain(held);
        assertThat(posts.detail(held, w.alice()).removed()).isTrue();
        assertThat(posts.detail(held, w.alice()).pending()).isFalse();
        assertThat(notifications.list(w.alice().id(), null, 10, false).items())
                .anyMatch(n -> n.type().equals("CONTENT_REMOVED"));
    }

    // ------------------------------------------------------------------ loose ends

    @Test
    void skippingOnboardingIsRemembered() {
        AuthUser u = user("sk" + sfx());
        assertThat(onboarding.skip(u).onboardingCompleted()).isTrue();
        var again = auth.login(u.username(), "password1", "t", "127.0.0.1").response().user();
        assertThat(again.onboardingCompleted()).isTrue();
    }

    @Test
    void feedRowsCarryModeratorRights() {
        World w = world();
        long p = post(w.alice(), w.slug(), "Rights check", "");
        assertThat(feeds.community(w.slug(), "new", null, null, 20, w.moderator()).items())
                .filteredOn(s -> s.id() == p).allMatch(s -> s.viewer().canModerate()).hasSize(1);
        assertThat(feeds.community(w.slug(), "new", null, null, 20, w.bob()).items())
                .filteredOn(s -> s.id() == p).noneMatch(s -> s.viewer().canModerate()).hasSize(1);
        assertThat(feeds.community(w.slug(), "new", null, null, 20, null).items())
                .filteredOn(s -> s.id() == p).noneMatch(s -> s.viewer().canModerate());
        AuthUser admin = user("tad" + sfx());
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(admin.id()).update();
        assertThat(feeds.home("latest", null, null, 50, new AuthUser(admin.id(), admin.username(), "ADMIN")).items())
                .filteredOn(s -> s.id() == p).allMatch(s -> s.viewer().canModerate()).hasSize(1);
    }
}
