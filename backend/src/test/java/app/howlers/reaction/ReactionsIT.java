package app.howlers.reaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.messaging.DmService;
import app.howlers.messaging.RoomService;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationService;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.reaction.ReactionService.ReactionCount;
import app.howlers.reaction.ReactionService.TargetReactions;
import app.howlers.reaction.ReactionService.TargetType;
import app.howlers.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import app.howlers.support.TestIds;

/** Emoji reactions (D-044): toggling, counts and names, visibility, blocks and notifications. */
class ReactionsIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired ReactionService reactions;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired ModerationService moderation;
    @Autowired DmService dms;
    @Autowired RoomService rooms;
    @Autowired JdbcClient jdbc;

    private AuthUser user(String prefix) {
        String name = prefix + TestIds.suffix();
        var me = auth.register(new RegisterRequest(name, name + "@react.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private String community(AuthUser owner, String visibility) {
        String slug = "rx" + TestIds.suffix();
        communities.create(owner, new CreateCommunityRequest(slug, "Reactions test", "", null, visibility, List.of(), List.of()));
        return slug;
    }

    private long post(AuthUser author, String slug) {
        return posts.create(author, new CreatePostRequest(slug, "TEXT", "A post " + System.nanoTime(), "<p>body</p>", null, null, null, null, null));
    }

    private static ReactionCount kind(TargetReactions t, String key) {
        return t.reactions().stream().filter(r -> r.reaction().equals(key)).findFirst().orElse(null);
    }

    private long notifications(AuthUser u) {
        return jdbc.sql("SELECT coalesce(sum(count), 0) FROM notifications WHERE user_id = ? AND type = 'REACTION'")
                .param(u.id()).query(Long.class).single();
    }

    @Test
    void togglingCountsNamesAndNotifications() {
        AuthUser ana = user("ana"), ben = user("ben"), cy = user("cy");
        long p = post(ana, community(ana, "PUBLIC"));

        var on = reactions.set(ben, TargetType.POST, p, "fire", null);
        assertThat(on.active()).isTrue();
        assertThat(kind(on.summary(), "fire").count()).isEqualTo(1);
        assertThat(kind(on.summary(), "fire").mine()).isTrue();
        assertThat(kind(on.summary(), "fire").emoji()).isEqualTo("🔥");
        // Idempotent "on", one of each kind per person.
        reactions.set(ben, TargetType.POST, p, "fire", true);
        reactions.set(cy, TargetType.POST, p, "FIRE", true);
        reactions.set(cy, TargetType.POST, p, "howl", true);

        TargetReactions s = reactions.summaries(TargetType.POST, List.of(p), ana).getFirst();
        assertThat(s.reactions()).extracting(ReactionCount::reaction).containsExactly("fire", "howl"); // catalog order
        assertThat(kind(s, "fire").count()).isEqualTo(2);
        assertThat(kind(s, "fire").sample()).containsExactly(ben.username(), cy.username());
        assertThat(kind(s, "fire").mine()).isFalse();
        // Anonymous readers see public counts too.
        assertThat(kind(reactions.summaries(TargetType.POST, List.of(p), null).getFirst(), "fire").count()).isEqualTo(2);

        var off = reactions.set(ben, TargetType.POST, p, "fire", null);
        assertThat(off.active()).isFalse();
        assertThat(kind(off.summary(), "fire").count()).isEqualTo(1);
        assertThat(kind(off.summary(), "fire").mine()).isFalse();

        assertThatThrownBy(() -> reactions.set(ben, TargetType.POST, p, "skull", true)).isInstanceOf(ApiException.class);
        // Two people reacted: one grouped notification bumped twice; reacting to your own post doesn't notify you.
        reactions.set(ana, TargetType.POST, p, "party", true);
        assertThat(notifications(ana)).isEqualTo(3);
    }

    @Test
    void blocksStopReactionsAndHideNames() {
        AuthUser ana = user("ana"), ben = user("ben"), dan = user("dan");
        long p = post(ana, community(ana, "PUBLIC"));
        reactions.set(dan, TargetType.POST, p, "like", true);
        reactions.set(ben, TargetType.POST, p, "like", true);
        long before = notifications(ana);

        moderation.block(ana, dan.username(), true);
        assertThatThrownBy(() -> reactions.set(dan, TargetType.POST, p, "heart", true)).isInstanceOf(ApiException.class);
        assertThat(notifications(ana)).isEqualTo(before);
        ReactionCount like = kind(reactions.summaries(TargetType.POST, List.of(p), ana).getFirst(), "like");
        assertThat(like.count()).isEqualTo(2);
        assertThat(like.sample()).containsExactly(ben.username()); // people you blocked aren't named to you
        assertThat(like.others()).isEqualTo(1);
    }

    @Test
    void youCanOnlyReactToContentYouCanSee() {
        AuthUser owner = user("own"), outsider = user("out"), member = user("mem");
        String secret = community(owner, "PRIVATE");
        long hidden = post(owner, secret);
        assertThatThrownBy(() -> reactions.set(outsider, TargetType.POST, hidden, "like", true)).isInstanceOf(ApiException.class);
        reactions.set(owner, TargetType.POST, hidden, "like", true);
        assertThat(reactions.summaries(TargetType.POST, List.of(hidden), outsider)).isEmpty();
        assertThat(reactions.summaries(TargetType.POST, List.of(hidden), null)).isEmpty();

        // Removed posts and held or deleted comments can't be reacted to, and their reactions aren't shown.
        String open = community(owner, "PUBLIC");
        long p = post(owner, open);
        long c = comments.create(p, member, new CreateCommentRequest("nice", null)).id();
        reactions.set(owner, TargetType.COMMENT, c, "clap", true);
        assertThat(kind(reactions.summaries(TargetType.COMMENT, List.of(c), member).getFirst(), "clap").count()).isEqualTo(1);
        jdbc.sql("UPDATE comments SET pending = TRUE, removed = TRUE WHERE id = ?").param(c).update();
        assertThatThrownBy(() -> reactions.set(owner, TargetType.COMMENT, c, "wow", true)).isInstanceOf(ApiException.class);
        assertThat(reactions.summaries(TargetType.COMMENT, List.of(c), member)).isEmpty();

        reactions.set(member, TargetType.POST, p, "wow", true);
        moderation.postAction(p, owner, Action.REMOVE_POST, "off topic");
        assertThatThrownBy(() -> reactions.set(member, TargetType.POST, p, "sad", true)).isInstanceOf(ApiException.class);
        assertThat(reactions.summaries(TargetType.POST, List.of(p), member)).isEmpty();
    }

    @Test
    void messageReactionsStayInsideTheConversation() {
        AuthUser ana = user("ana"), ben = user("ben"), eve = user("eve"), sol = user("sol");
        var convo = dms.start(ana, ben.username());
        long m = dms.send(convo.id(), ben, new SendRequest("hi", null)).id();
        reactions.set(ana, TargetType.MESSAGE, m, "heart", true);
        assertThat(kind(reactions.summaries(TargetType.MESSAGE, List.of(m), ben).getFirst(), "heart").sample())
                .containsExactly(ana.username());
        assertThatThrownBy(() -> reactions.set(eve, TargetType.MESSAGE, m, "heart", true)).isInstanceOf(ApiException.class);
        assertThat(reactions.summaries(TargetType.MESSAGE, List.of(m), eve)).isEmpty();
        assertThatThrownBy(() -> reactions.summaries(TargetType.MESSAGE, List.of(m), null)).isInstanceOf(ApiException.class);

        // Pack rooms: system lines can't be reacted to, nor messages from someone you blocked.
        long room = rooms.create(ana, "Pack", null, List.of(ben.username(), sol.username())).id();
        long systemLine = dms.messages(room, ana, null, 30).items().getFirst().id();
        assertThatThrownBy(() -> reactions.set(ben, TargetType.MESSAGE, systemLine, "like", true)).isInstanceOf(ApiException.class);
        moderation.block(sol, ben.username(), true);
        long fromBen = dms.send(room, ben, new SendRequest("hey pack", null)).id();
        assertThatThrownBy(() -> reactions.set(sol, TargetType.MESSAGE, fromBen, "like", true)).isInstanceOf(ApiException.class);
        assertThat(reactions.set(ana, TargetType.MESSAGE, fromBen, "howl", true).active()).isTrue();

        // Deleting a message clears its reactions.
        dms.delete(fromBen, ben);
        assertThat(reactions.summaries(TargetType.MESSAGE, List.of(fromBen), ana)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM reactions WHERE target_type = 'MESSAGE' AND target_id = ?").param(fromBen)
                .query(Long.class).single()).isZero();
    }
}
