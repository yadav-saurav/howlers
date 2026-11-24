package app.howlers.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.ai.AiDtos.AskRequest;
import app.howlers.ai.AiDtos.Source;
import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs with the offline provider (application-test.yml), so no network or API key is needed. */
class CommunityAiIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired AiService ai;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@ai.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private String sfx() {
        return Long.toString(System.nanoTime() % 1_000_000);
    }

    @Test
    void answersWithValidCitationsFromVisibleContentOnly() {
        String s = sfx();
        AuthUser a = user("aia" + s);
        AuthUser reader = user("air" + s);
        String pub = "aipub" + s;
        String priv = "aipriv" + s;
        communities.create(a, new CreateCommunityRequest(pub, "Public", "", null, "PUBLIC", List.of(), List.of()));
        communities.create(a, new CreateCommunityRequest(priv, "Private", "", null, "PRIVATE", List.of(), List.of()));

        long post = posts.create(a, new CreatePostRequest(pub, "TEXT", "Learning zygomorphic ownership in Rust",
                "<p>How long did zygomorphic borrow checking take you to learn?</p>", null, null, null, null, null));
        long good = comments.create(post, reader, new CreateCommentRequest("Zygomorphic ownership clicked for me after about six weeks of practice.", null)).id();
        long deleted = comments.create(post, a, new CreateCommentRequest("Zygomorphic secret that was later deleted.", null)).id();
        comments.delete(deleted, a);
        long hidden = posts.create(a, new CreatePostRequest(priv, "TEXT", "Zygomorphic private roadmap",
                "<p>Members-only zygomorphic notes.</p>", null, null, null, null, null));

        var res = ai.ask(reader, new AskRequest(null, "How long does zygomorphic ownership take to learn?", null, null, null));
        List<Source> src = res.answer().sources();
        assertThat(src).isNotEmpty();
        assertThat(src).extracting(Source::postId).contains(post).doesNotContain(hidden);
        assertThat(src).extracting(Source::commentId).contains(good).doesNotContain(deleted);
        assertThat(res.answer().engine()).contains("Offline");
        assertThat(res.answer().notice()).contains("No AI model is configured");

        // Every citation in the answer maps to a returned source.
        var m = java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(res.answer().content());
        int citations = 0;
        while (m.find()) {
            assertThat(Integer.parseInt(m.group(1))).isBetween(1, src.size());
            citations++;
        }
        assertThat(citations).isGreaterThan(0);

        // The owner (a member) does see the private post.
        var ownerRes = ai.ask(a, new AskRequest(null, "zygomorphic roadmap", null, null, null));
        assertThat(ownerRes.answer().sources()).extracting(Source::postId).contains(hidden);

        // Follow-up in the same conversation, and ownership is enforced.
        var follow = ai.ask(reader, new AskRequest(res.conversation().id(), "Any zygomorphic tips?", null, null, null));
        assertThat(follow.conversation().id()).isEqualTo(res.conversation().id());
        assertThat(ai.get(reader, res.conversation().id()).messages()).hasSize(4);
        assertThatThrownBy(() -> ai.get(a, res.conversation().id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ai.feedback(a, res.answer().id(), 1)).isInstanceOf(ApiException.class);
        ai.feedback(reader, res.answer().id(), 1);
        assertThat(ai.get(reader, res.conversation().id()).messages().get(1).feedback()).isEqualTo(1);
    }

    @Test
    void scopesAreCheckedAgainstPermissions() {
        String s = sfx();
        AuthUser a = user("asa" + s);
        AuthUser b = user("asb" + s);
        String priv = "asp" + s;
        communities.create(a, new CreateCommunityRequest(priv, "Private", "", null, "PRIVATE", List.of(), List.of()));
        long post = posts.create(a, new CreatePostRequest(priv, "TEXT", "Members only", "<p>x</p>", null, null, null, null, null));
        assertThatThrownBy(() -> ai.ask(b, new AskRequest(null, "what is here?", priv, null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ai.ask(b, new AskRequest(null, "summarise this thread", null, post, null))).isInstanceOf(ApiException.class);
        var own = ai.ask(a, new AskRequest(null, "summarise this thread", null, post, null));
        assertThat(own.answer().sources()).extracting(Source::postId).containsOnly(post);
    }

    @Test
    void stripsCitationsThatDoNotMapToSources() {
        Source one = new Source(1, "POST", 1, null, "t", "c", "u", 1, Instant.now(), "x", "/c/c/p/1", false);
        Source two = new Source(2, "POST", 2, null, "t", "c", "u", 1, Instant.now(), "x", "/c/c/p/2", false);
        var v = AiService.validateCitations("Yes [1]. Also [7] and [2][0].", List.of(one, two));
        assertThat(v.text()).isEqualTo("Yes [1]. Also  and [2].");
        assertThat(v.sources()).extracting(Source::cited).containsExactly(true, true);
    }

    @Test
    void buildsSafeKeywordQueriesAndIntents() {
        assertThat(QueryAnalyzer.tsQuery("What do developers think about Rust compared with Go?")).isEqualTo("rust:* | go");
        assertThat(QueryAnalyzer.tsQuery("'); DROP TABLE posts; --")).isEqualTo("drop:* | table:*");
        assertThat(QueryAnalyzer.tsQuery("how is it?")).isNull();
        assertThat(QueryAnalyzer.tsQuery("What mistakes do beginners repeatedly make?")).isEqualTo("mistakes:* | beginners:*");
        assertThat(QueryAnalyzer.intent("Rust vs Go for backends")).isEqualTo(AiDtos.Intent.COMPARE);
        assertThat(QueryAnalyzer.intent("best resources for learning cybersecurity")).isEqualTo(AiDtos.Intent.RESOURCES);
        assertThat(QueryAnalyzer.intent("Which discussions have conflicting opinions?")).isEqualTo(AiDtos.Intent.DEBATE);
    }
}
