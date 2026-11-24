package app.howlers.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.collection.CollectionDtos.AddItem;
import app.howlers.collection.CollectionDtos.UpdateCollection;
import app.howlers.collection.CollectionDtos.UpdateItem;
import app.howlers.collection.CollectionService;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.insight.InsightDtos.ArgItem;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import app.howlers.support.TestIds;

class KnowledgeLayerIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired ContextService context;
    @Autowired DigestService digests;
    @Autowired CollectionService collections;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@k.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private record Thread(AuthUser author, AuthUser other, long postId, List<Long> commentIds, String slug) {}

    private Thread thread(String visibility) {
        String s = TestIds.suffix();
        AuthUser a = user("ka" + s);
        AuthUser b = user("kb" + s);
        String slug = "kl" + s;
        communities.create(a, new CreateCommunityRequest(slug, "Knowledge", "", null, visibility, List.of(), List.of()));
        long postId = posts.create(a, new CreatePostRequest(slug, "TEXT", "Is Rust worth learning in 2026?",
                "<p>Thinking about it for backend work.</p>", null, null, null, null, null));
        AuthUser commenter = "PRIVATE".equals(visibility) ? a : b;
        List<String> bodies = List.of(
                "I disagree, Go gets you to production faster for most backend teams.",
                "Agree! We switched in 2024 and our p99 latency dropped by 40% within two months.",
                "What about the learning curve for juniors?",
                "Source with numbers: https://example.com/survey shows 72% of respondents would use it again.",
                "We used it for a payments service and the compiler caught bugs our reviews missed.");
        List<Long> ids = bodies.stream().map(body -> comments.create(postId, commenter, new CreateCommentRequest(body, null)).id()).toList();
        return new Thread(a, b, postId, ids, slug);
    }

    @Test
    void contextModeGroupsArgumentsAndLetsPeopleCorrectLabels() {
        Thread t = thread("PUBLIC");
        var view = context.build(t.postId(), t.author());
        assertThat(view.stats().analysed()).isEqualTo(5);
        assertThat(ids(view.groups().get(Stance.OPPOSE))).contains(t.commentIds().get(0));
        assertThat(ids(view.groups().get(Stance.SUPPORT))).contains(t.commentIds().get(1));
        assertThat(ids(view.groups().get(Stance.QUESTION))).contains(t.commentIds().get(2));
        assertThat(view.openQuestions()).extracting(ArgItem::commentId).contains(t.commentIds().get(2));
        assertThat(view.evidence()).anySatisfy(e -> {
            assertThat(e.url()).isEqualTo("https://example.com/survey");
            assertThat(e.commentId()).isEqualTo(t.commentIds().get(3));
        });
        assertThat(view.groups().values().stream().flatMap(List::stream)).allMatch(ArgItem::suggested);

        // A human label overrides the heuristic and is no longer "suggested".
        long q = t.commentIds().get(2);
        context.label(q, t.other(), Stance.OPPOSE);
        var after = context.build(t.postId(), t.other());
        ArgItem item = after.groups().get(Stance.OPPOSE).stream().filter(i -> i.commentId() == q).findFirst().orElseThrow();
        assertThat(item.suggested()).isFalse();
        assertThat(item.myLabel()).isEqualTo(Stance.OPPOSE);
    }

    @Test
    void digestOnlyQuotesRealCommentsAndTracksStaleness() {
        Thread t = thread("PUBLIC");
        var d = digests.get(t.postId(), "standard", "balanced", t.other());
        Set<Long> real = new HashSet<>(t.commentIds());
        assertThat(d.sections().summary()).isNotEmpty();
        assertThat(d.sections().summary()).allSatisfy(l -> assertThat(real).contains(l.commentId()));
        assertThat(d.sections().takeaways()).allSatisfy(l -> assertThat(real).contains(l.commentId()));
        assertThat(d.sections().consensus().status()).isIn("DIVIDED", "INSUFFICIENT"); // 1 for vs 1 against: never "consensus"
        assertThat(d.stale()).isFalse();
        assertThat(d.engine()).isEqualTo("extractive-v2");

        var cached = digests.get(t.postId(), "standard", "balanced", t.other());
        assertThat(cached.generatedAt()).isEqualTo(d.generatedAt());

        for (int i = 0; i < 5; i++) {
            comments.create(t.postId(), t.other(), new CreateCommentRequest("Another perspective number " + i + " on the thread.", null));
        }
        assertThat(digests.get(t.postId(), "standard", "balanced", t.other()).stale()).isTrue();
        assertThat(DigestService.isStale(100, 104)).isFalse();
    }

    @Test
    void collectionsArePrivateOrderedAndShareSafely() {
        Thread t = thread("PUBLIC");
        AuthUser me = t.other();
        var c = collections.create(me, "Rust research", "Reading list");
        var other = collections.create(me, "Read later", null);
        var item = collections.add(me, c.id(), new AddItem("POST", t.postId(), null, "Good overview", List.of("rust")));
        var comment = collections.add(me, c.id(), new AddItem("COMMENT", t.postId(), t.commentIds().get(1), null, List.of("data")));
        assertThatThrownBy(() -> collections.add(me, c.id(), new AddItem("POST", t.postId(), null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Already");

        // Owner-only: another user can't read or modify it.
        assertThatThrownBy(() -> collections.items(t.author(), c.id(), null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> collections.removeItem(t.author(), item.id())).isInstanceOf(ApiException.class);

        assertThat(collections.items(me, c.id(), "overview", null, null)).extracting(i -> i.id()).containsExactly(item.id());
        assertThat(collections.items(me, null, null, "data", null)).extracting(i -> i.id()).containsExactly(comment.id());
        assertThat(comment.comment().excerpt()).contains("p99");

        collections.reorder(me, c.id(), List.of(comment.id(), item.id()));
        assertThat(collections.items(me, c.id(), null, null, null)).extracting(i -> i.id()).containsExactly(comment.id(), item.id());

        collections.updateItem(me, comment.id(), new UpdateItem("moved", null, other.id()));
        assertThat(collections.items(me, other.id(), null, null, null)).extracting(i -> i.note()).containsExactly("moved");

        assertThat(c.shared()).isFalse();
        var shared = collections.update(me, c.id(), new UpdateCollection(null, null, true));
        assertThat(shared.shareToken()).isNotBlank();
        assertThat(collections.shared(shared.shareToken(), null).items()).hasSize(1);
        collections.update(me, c.id(), new UpdateCollection(null, null, false));
        assertThatThrownBy(() -> collections.shared(shared.shareToken(), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void privateCommunityThreadsAreNotAnalysableByOutsiders() {
        Thread t = thread("PRIVATE");
        assertThatThrownBy(() -> context.build(t.postId(), t.other())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> digests.get(t.postId(), "short", "balanced", t.other())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> digests.get(t.postId(), "short", "balanced", null)).isInstanceOf(ApiException.class);
    }

    private static List<Long> ids(List<ArgItem> items) {
        return items.stream().map(ArgItem::commentId).toList();
    }
}
