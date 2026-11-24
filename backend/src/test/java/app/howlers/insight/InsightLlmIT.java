package app.howlers.insight;

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
import app.howlers.insight.InsightDtos.ArgItem;
import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.InsightFixtures.FakeLlm;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * LLM-backed digests and stance suggestions end to end, with a fake standing in for the real model (the suite never
 * calls the real API). The fake answers from the prompt it receives, so these tests also check what is sent.
 */
class InsightLlmIT extends IntegrationTest {
    private static final Pattern COMMENT = Pattern.compile("<comment id=\"(\\d+)\"[^>]*>\\n(?:<parent>.*?</parent>\\n)?(.*?)\\n</comment>", Pattern.DOTALL);

    static final FakeLlm FAKE = new FakeLlm(InsightLlmIT::answer);

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {
        @Bean
        @Primary
        InsightLlm fakeInsightLlm() {
            return FAKE;
        }
    }

    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired CommentService comments;
    @Autowired ContextService context;
    @Autowired DigestService digests;
    @Autowired StanceSuggester suggester;
    @Autowired JdbcClient jdbc;

    /** Valid output built from the prompt: cites the first comment, quotes it verbatim, labels every comment EVIDENCE. */
    static InsightLlm.Completion answer(String user) {
        Matcher m = COMMENT.matcher(user);
        List<long[]> ids = new ArrayList<>();
        String firstText = null;
        while (m.find()) {
            ids.add(new long[]{Long.parseLong(m.group(1))});
            if (firstText == null) firstText = m.group(2);
        }
        if (user.startsWith("Label each comment")) {
            String labels = String.join(",", ids.stream().map(i -> "{\"commentId\": " + i[0] + ", \"stance\": \"EVIDENCE\"}").toList());
            return FakeLlm.ok("{\"labels\": [" + labels + "]}");
        }
        long first = ids.getFirst()[0];
        String quote = firstText.substring(0, Math.min(30, firstText.length())).replace("\"", "\\\"");
        return FakeLlm.ok("""
                {"summary": [{"text": "People weigh Rust against Go.", "kind": "opinion", "commentIds": [%d, 424242]}],
                 "supporting": [], "opposing": [{"text": "Invented point.", "kind": "fact", "commentIds": [424242]}],
                 "quotes": [{"commentId": %d, "quote": "%s", "kind": "opinion"}], "openQuestions": []}""".formatted(first, first, quote));
    }

    @BeforeEach
    void reset() {
        FAKE.script = InsightLlmIT::answer;
        FAKE.available = true;
        FAKE.userTurns.clear();
        FAKE.systems.clear();
    }

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@llm.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private record Thread(AuthUser owner, AuthUser reader, long postId, List<Long> ids) {}

    private Thread thread(String visibility) {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        AuthUser a = user("la" + s);
        AuthUser b = user("lb" + s);
        String slug = "ll" + s;
        communities.create(a, new CreateCommunityRequest(slug, "LLM insight", "", null, visibility, List.of(), List.of()));
        long postId = posts.create(a, new CreatePostRequest(slug, "TEXT", "Is Rust worth learning in 2026?",
                "<p>Thinking about it for backend work.</p>", null, null, null, null, null));
        AuthUser commenter = "PRIVATE".equals(visibility) ? a : b;
        List<Long> ids = Stream.of(
                "I disagree, Go gets you to production faster for most backend teams.",
                "We switched in 2024 and our p99 latency dropped by 40% within two months.",
                "What about the learning curve for juniors on a small team?",
                "We used it for a payments service and the compiler caught bugs our reviews missed.",
                "Honestly the tooling has been the best part of the move for us.")
                .map(body -> comments.create(postId, commenter, new CreateCommentRequest(body, null)).id()).toList();
        return new Thread(a, b, postId, ids);
    }

    @Test
    void signedInReadersGetAVerifiedAiDigestThatIsCached() {
        Thread t = thread("PUBLIC");
        var d = digests.get(t.postId(), "standard", "balanced", t.reader());
        assertThat(d.engine()).isEqualTo(LlmDigestEngine.ID);
        assertThat(d.notice()).isNull();
        assertThat(d.engineNote()).contains("AI model (fake)", "1 point was removed");
        // The fabricated id (424242) was stripped from the summary point and the invented opposing point dropped.
        assertThat(d.sections().summary()).singleElement().satisfies(l -> assertThat(l.commentIds()).isSubsetOf(t.ids()).hasSize(1));
        assertThat(d.sections().opposing()).isEmpty();
        assertThat(d.sections().takeaways()).singleElement().satisfies(q -> assertThat(q.paraphrase()).isFalse());
        assertThat(d.sections().coverage().included()).isEqualTo(5);
        assertThat(FAKE.userTurns).hasSize(1);

        var again = digests.get(t.postId(), "standard", "balanced", t.owner());
        assertThat(again.engine()).isEqualTo(LlmDigestEngine.ID);
        assertThat(again.sections().summary()).isEqualTo(d.sections().summary());
        assertThat(FAKE.userTurns).hasSize(1); // served from cache
        Integer tokens = jdbc.sql("SELECT input_tokens FROM thread_digests WHERE post_id = ? AND length = 'standard' AND focus = 'balanced'")
                .param(t.postId()).query(Integer.class).single();
        assertThat(tokens).isEqualTo(1200);
        assertThat(jdbc.sql("SELECT count(*) FROM insight_llm_usage WHERE post_id = ? AND operation = 'DIGEST'")
                .param(t.postId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anonymousViewsNeverCallTheModel() {
        Thread t = thread("PUBLIC");
        var anon = digests.get(t.postId(), "short", "balanced", null);
        assertThat(anon.engine()).isEqualTo(ExtractiveDigestEngine.ID);
        assertThat(anon.notice()).contains("Sign in");
        assertThat(FAKE.userTurns).isEmpty();
        // A signed-in view upgrades the cached extractive digest to an AI digest.
        assertThat(digests.get(t.postId(), "short", "balanced", t.reader()).engine()).isEqualTo(LlmDigestEngine.ID);
        assertThat(digests.get(t.postId(), "short", "balanced", null).engine()).isEqualTo(LlmDigestEngine.ID);
        assertThat(FAKE.userTurns).hasSize(1);
    }

    @Test
    void providerFailureFallsBackToExtractiveAndSaysSo() {
        Thread t = thread("PUBLIC");
        FAKE.script = u -> { throw new IllegalStateException("boom"); };
        var d = digests.get(t.postId(), "standard", "top", t.reader());
        assertThat(d.engine()).isEqualTo(ExtractiveDigestEngine.ID);
        assertThat(d.notice()).contains("The AI model couldn't produce this digest", "couldn't be reached");
        assertThat(d.engineNote()).startsWith("Extractive summary");
        assertThat(d.sections().summary()).isNotEmpty();
        // The fallback is cached for a while, so a failing provider isn't called on every view.
        assertThat(digests.get(t.postId(), "standard", "top", t.reader()).notice()).contains("couldn't be reached");
        assertThat(FAKE.userTurns).hasSize(1);

        FAKE.script = u -> FakeLlm.ok("{\"summary\": \"nope\"}"); // schema-violating output
        var bad = digests.refresh(t.postId(), "standard", "top", t.reader());
        assertThat(bad.engine()).isEqualTo(ExtractiveDigestEngine.ID);
        assertThat(bad.notice()).contains("missing");

        FAKE.available = false; // no key configured: extractive, no notice, no calls
        var off = digests.refresh(t.postId(), "standard", "top", t.reader());
        assertThat(off.engine()).isEqualTo(ExtractiveDigestEngine.ID);
        assertThat(off.notice()).isNull();
        assertThat(off.engineNote()).contains("No AI provider is configured");
        assertThat(FAKE.userTurns).hasSize(2);
    }

    @Test
    void removedHeldAndDeletedCommentsNeverReachTheModelOrTheDigest() {
        Thread t = thread("PUBLIC");
        long removed = t.ids().get(1);
        long held = t.ids().get(2);
        jdbc.sql("UPDATE comments SET removed = TRUE WHERE id = ?").param(removed).update();
        jdbc.sql("UPDATE comments SET pending = TRUE WHERE id = ?").param(held).update();
        var d = digests.get(t.postId(), "standard", "balanced", t.reader());
        assertThat(d.engine()).isEqualTo(LlmDigestEngine.ID);
        String sent = FAKE.userTurns.getFirst();
        assertThat(sent).doesNotContain("p99 latency", "learning curve", "id=\"" + removed + "\"", "id=\"" + held + "\"");
        assertThat(d.sections().coverage().total()).isEqualTo(3);

        // Deleting the cited comment afterwards: the cached digest stops showing it and is marked stale.
        long cited = d.sections().summary().getFirst().commentId();
        jdbc.sql("UPDATE comments SET deleted_at = now() WHERE id = ?").param(cited).update();
        var after = digests.get(t.postId(), "standard", "balanced", t.reader());
        assertThat(after.stale()).isTrue();
        assertThat(Stream.of(after.sections().summary(), after.sections().takeaways()).flatMap(List::stream).map(Line::commentId))
                .doesNotContain(cited);
        assertThat(after.sections().mostUseful()).extracting(ArgItem::commentId).doesNotContain(cited);
    }

    @Test
    void privateThreadsAreNeverSentForOutsiders() {
        Thread t = thread("PRIVATE");
        AuthUser outsider = user("lo" + (System.nanoTime() % 1_000_000));
        assertThatThrownBy(() -> digests.get(t.postId(), "standard", "balanced", outsider)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> context.build(t.postId(), outsider)).isInstanceOf(ApiException.class);
        assertThat(FAKE.userTurns).isEmpty();
    }

    @Test
    void aiStancesAreSuggestedInTheBackgroundAndReaderLabelsWin() throws Exception {
        Thread t = thread("PUBLIC");
        assertThat(context.build(t.postId(), null).aiStances().state()).isEqualTo("PARTIAL"); // anonymous: no job
        var first = context.build(t.postId(), t.reader());
        assertThat(first.aiStances().state()).isEqualTo("PENDING");
        for (int i = 0; i < 100 && suggester.isRunning(t.postId()); i++) java.lang.Thread.sleep(50);

        var view = context.build(t.postId(), t.reader());
        assertThat(view.aiStances().state()).isEqualTo("READY");
        assertThat(view.stats().aiSuggested()).isEqualTo(5);
        List<ArgItem> evidence = view.groups().get(Stance.EVIDENCE);
        assertThat(evidence).hasSize(5).allSatisfy(i -> {
            assertThat(i.stanceSource()).isEqualTo(StanceSource.AI);
            assertThat(i.suggested()).isTrue();
        });
        assertThat(FAKE.userTurns).singleElement().satisfies(u -> assertThat(u).startsWith("Label each comment"));

        // A reader label always wins over the AI suggestion.
        long q = t.ids().get(2);
        context.label(q, t.owner(), Stance.QUESTION);
        ArgItem labelled = context.build(t.postId(), t.owner()).groups().get(Stance.QUESTION).stream()
                .filter(i -> i.commentId() == q).findFirst().orElseThrow();
        assertThat(labelled.stanceSource()).isEqualTo(StanceSource.READERS);
        assertThat(labelled.suggested()).isFalse();

        // Editing a comment invalidates its cached suggestion (rules apply until it is re-suggested).
        long edited = t.ids().get(0);
        jdbc.sql("UPDATE comments SET body = 'Edited: what do you all think?', edited_at = now() WHERE id = ?").param(edited).update();
        ArgItem after = context.build(t.postId(), null).groups().values().stream().flatMap(List::stream)
                .filter(i -> i.commentId() == edited).findFirst().orElseThrow();
        assertThat(after.stanceSource()).isEqualTo(StanceSource.RULES);
    }
}
