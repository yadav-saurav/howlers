package app.howlers.moderation;

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
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.notification.NotificationService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.pulse.PulseDtos.CreatePulse;
import app.howlers.pulse.PulseDtos.OptionDto;
import app.howlers.pulse.PulseService;
import app.howlers.support.IntegrationTest;
import app.howlers.vote.VoteService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ModerationAndPulseIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired VoteService votes;
    @Autowired FeedService feeds;
    @Autowired ModerationService mod;
    @Autowired PulseService pulses;
    @Autowired NotificationService notifications;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@m.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private record World(AuthUser owner, AuthUser moderator, AuthUser member, AuthUser reporter, String slug, long postId) {}

    private World world() {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        AuthUser owner = user("mo" + s), moderator = user("mm" + s), member = user("me" + s), reporter = user("mr" + s);
        String slug = "mod" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Mod test", "", null, "PUBLIC", List.of(), List.of()));
        for (AuthUser u : List.of(moderator, member, reporter)) communities.join(slug, u);
        communities.setRole(slug, owner, moderator.username(), CommunityRole.MODERATOR);
        long postId = posts.create(member, new CreatePostRequest(slug, "TEXT", "Totally legit offer", "<p>buy now</p>", null, null, null, null, null));
        return new World(owner, moderator, member, reporter, slug, postId);
    }

    @Test
    void reportQueueRemovalAppealAndAuditTrail() {
        World w = world();
        mod.report(w.reporter(), new ReportRequest("POST", w.postId(), null, "Spam", "Link farm"));
        mod.report(w.owner(), new ReportRequest("POST", w.postId(), null, "Spam", null));
        assertThatThrownBy(() -> mod.report(w.reporter(), new ReportRequest("POST", w.postId(), null, "Spam", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("already reported");
        assertThatThrownBy(() -> mod.report(w.member(), new ReportRequest("POST", w.postId(), null, "Spam", null)))
                .isInstanceOf(ApiException.class); // can't report your own post

        assertThatThrownBy(() -> mod.queue(w.slug(), w.member())).isInstanceOf(ApiException.class);
        var queue = mod.queue(w.slug(), w.moderator());
        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().reportCount()).isEqualTo(2);
        assertThat(queue.getFirst().details()).containsExactly("Link farm");

        mod.postAction(w.postId(), w.moderator(), Action.REMOVE_POST, "Spam");
        assertThat(mod.queue(w.slug(), w.moderator())).isEmpty();
        assertThat(feeds.community(w.slug(), "new", null, null, 20, w.reporter()).items()).extracting(PostSummary::id).doesNotContain(w.postId());

        // The author learns why, with a link to appeal.
        var inbox = notifications.list(w.member().id(), null, 10, false).items();
        assertThat(inbox).anySatisfy(n -> assertThat(n.type()).isEqualTo("CONTENT_REMOVED"));
        long actionId = Long.parseLong(inbox.stream().filter(n -> n.type().equals("CONTENT_REMOVED")).findFirst().orElseThrow()
                .link().replace("/mod-actions/", ""));
        assertThatThrownBy(() -> mod.myAction(actionId, w.reporter())).isInstanceOf(ApiException.class); // only the affected user
        assertThat(mod.myAction(actionId, w.member()).canAppeal()).isTrue();
        // Actions are taken on behalf of the team (D-014): the affected user never learns which moderator acted.
        assertThat(mod.myAction(actionId, w.member()).action().moderator()).isNull();
        var appeal = mod.submitAppeal(actionId, w.member(), "It was a genuine offer from me, not spam.");
        assertThat(appeal.action().moderator()).isNull();
        assertThat(mod.myAction(actionId, w.member()).appeal().action().moderator()).isNull();
        // Moderators still see who acted.
        assertThat(mod.log(w.slug(), w.owner(), null, 20).items()).anySatisfy(a -> assertThat(a.moderator()).isNotNull());
        assertThatThrownBy(() -> mod.submitAppeal(actionId, w.member(), "Second attempt at appealing this")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> mod.resolveAppeal(appeal.id(), w.member(), true, null)).isInstanceOf(ApiException.class);
        mod.resolveAppeal(appeal.id(), w.moderator(), true, "Fair enough");

        assertThat(feeds.community(w.slug(), "new", null, null, 20, w.reporter()).items()).extracting(PostSummary::id).contains(w.postId());
        var log = mod.log(w.slug(), w.owner(), null, 20).items();
        assertThat(log).extracting(ModerationDtos.ModAction::action).contains("REMOVE_POST");
        assertThat(log.stream().filter(a -> a.action().equals("REMOVE_POST")).findFirst().orElseThrow().reversed()).isTrue();
    }

    @Test
    void bansBlockParticipationAndRespectRank() {
        World w = world();
        long comment = comments.create(w.postId(), w.reporter(), new CreateCommentRequest("Hello there friends", null)).id();
        mod.ban(w.slug(), w.moderator(), w.reporter().username(), "Harassment", 7);

        assertThat(communities.detail(w.slug(), w.reporter()).viewer().banned()).isTrue();
        assertThatThrownBy(() -> posts.create(w.reporter(), new CreatePostRequest(w.slug(), "TEXT", "Back again", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("banned");
        assertThatThrownBy(() -> comments.create(w.postId(), w.reporter(), new CreateCommentRequest("still here", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("banned");
        assertThatThrownBy(() -> votes.votePost(w.postId(), w.reporter(), 1)).isInstanceOf(ApiException.class);
        assertThat(comment).isPositive();

        // Rank: a moderator can't ban the owner or another moderator; the owner can ban the moderator.
        assertThatThrownBy(() -> mod.ban(w.slug(), w.moderator(), w.owner().username(), "x", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> mod.ban(w.slug(), w.member(), w.reporter().username(), "x", null)).isInstanceOf(ApiException.class);

        mod.unban(w.slug(), w.moderator(), w.reporter().username());
        assertThat(communities.detail(w.slug(), w.reporter()).viewer().banned()).isFalse();
        comments.create(w.postId(), w.reporter(), new CreateCommentRequest("Glad to be back", null));
    }

    @Test
    void pinLimitAndBlocking() {
        World w = world();
        for (int i = 0; i < 3; i++) {
            long id = posts.create(w.member(), new CreatePostRequest(w.slug(), "TEXT", "Pin me " + i, null, null, null, null, null, null));
            mod.postAction(id, w.moderator(), Action.PIN, null);
        }
        long fourth = posts.create(w.member(), new CreatePostRequest(w.slug(), "TEXT", "One too many", null, null, null, null, null, null));
        assertThatThrownBy(() -> mod.postAction(fourth, w.moderator(), Action.PIN, null)).isInstanceOf(ApiException.class);

        long c = comments.create(w.postId(), w.member(), new CreateCommentRequest("A comment by the blocked person", null)).id();
        mod.block(w.reporter(), w.member().username(), true);
        assertThat(feeds.community(w.slug(), "new", null, null, 50, w.reporter()).items())
                .noneMatch(p -> p.author() != null && p.author().id() == w.member().id());
        CommentNode node = comments.topLevel(w.postId(), w.reporter(), "new", null, 20).items().stream()
                .filter(n -> n.id() == c).findFirst().orElseThrow();
        assertThat(node.blocked()).isTrue();
        assertThat(node.body()).isEqualTo("[blocked user]");
        assertThat(node.author()).isNull();
        assertThat(mod.blocks(w.reporter())).extracting(u -> u.id()).containsExactly(w.member().id());
    }

    @Test
    void pulseVotingResultsAndPredictions() {
        World w = world();
        CreatePulse poll = new CreatePulse("POLL", "Best editor?", null, List.of("Vim", "Emacs", "VS Code"), false, false, false, 24);
        assertThatThrownBy(() -> pulses.create(w.slug(), w.member(), poll)).isInstanceOf(ApiException.class);
        var p = pulses.create(w.slug(), w.moderator(), poll);
        assertThat(p.status()).isEqualTo("OPEN");

        var before = pulses.get(p.id(), w.member());
        assertThat(before.resultsVisible()).isFalse();
        assertThat(before.options()).extracting(OptionDto::votes).containsOnlyNulls();

        long vim = p.options().get(0).id(), code = p.options().get(2).id();
        var after = pulses.vote(p.id(), w.member(), List.of(vim), null);
        assertThat(after.resultsVisible()).isTrue();
        assertThat(after.options().getFirst().votes()).isEqualTo(1);
        assertThatThrownBy(() -> pulses.vote(p.id(), w.member(), List.of(code), null)).isInstanceOf(ApiException.class); // allowChange=false
        assertThatThrownBy(() -> pulses.vote(p.id(), w.reporter(), List.of(vim, code), null)).isInstanceOf(ApiException.class); // single choice

        var pred = pulses.create(w.slug(), w.moderator(), new CreatePulse("PREDICTION", "Will it ship this month?", null,
                List.of("Yes", "No"), false, true, true, 24));
        long yes = pred.options().get(0).id(), no = pred.options().get(1).id();
        pulses.vote(pred.id(), w.member(), List.of(yes), "Team is on track");
        pulses.vote(pred.id(), w.reporter(), List.of(no), null);
        pulses.vote(pred.id(), w.reporter(), List.of(yes), null); // change allowed
        assertThat(pulses.get(pred.id(), w.reporter()).totalVoters()).isEqualTo(2);
        assertThatThrownBy(() -> pulses.resolve(pred.id(), w.moderator(), yes)).isInstanceOf(ApiException.class); // still open
        pulses.close(pred.id(), w.moderator());
        assertThatThrownBy(() -> pulses.vote(pred.id(), w.owner(), List.of(no), null)).isInstanceOf(ApiException.class);
        var resolved = pulses.resolve(pred.id(), w.moderator(), yes);
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.accuracy()).isEqualTo(1.0);
        assertThat(pulses.reasons(pred.id(), w.owner())).singleElement()
                .satisfies(r -> assertThat(r.author()).isNull()); // anonymous pulse hides who said it
        assertThat(notifications.list(w.member().id(), null, 10, false).items())
                .anySatisfy(n -> assertThat(n.type()).isEqualTo("PULSE_RESULT"));

        mod.ban(w.slug(), w.moderator(), w.member().username(), "Cooling off", 1);
        var later = pulses.create(w.slug(), w.moderator(), poll);
        assertThat(pulses.get(later.id(), w.member()).viewer().canVote()).isFalse();
        assertThatThrownBy(() -> pulses.vote(later.id(), w.member(), List.of(later.options().getFirst().id()), null))
                .isInstanceOf(ApiException.class);
    }
}
