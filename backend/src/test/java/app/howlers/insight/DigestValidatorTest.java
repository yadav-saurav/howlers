package app.howlers.insight;

import static app.howlers.insight.InsightFixtures.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.ThreadLoader.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigestValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Node> sent = List.of(
            node(11, "We moved our billing service to Rust and p99 latency dropped by 40% in two months.", Stance.EXPERIENCE),
            node(12, "I disagree.   Go gets most backend teams\nto production faster, and hiring is easier.", Stance.OPPOSE),
            node(13, "What about the learning curve for junior developers on the team?", Stance.QUESTION));
    private final InsightPrompts.Shape shape = InsightPrompts.shape("standard");

    private DigestValidator.Result run(String out) {
        return DigestValidator.validate(out, sent, shape, json);
    }

    private static String doc(String summary, String quotes) {
        return """
                {"summary": %s, "supporting": [], "opposing": [], "quotes": %s, "openQuestions": []}""".formatted(summary, quotes);
    }

    @Test
    void keepsValidPointsAndVerbatimQuotes() {
        var r = run(doc("""
                [{"text": "One team reports a large latency drop after moving to Rust.", "kind": "anecdote", "commentIds": [11]}]""", """
                [{"commentId": 11, "quote": "p99 latency dropped by 40% in two months", "kind": "fact"}]"""));
        assertThat(r.summary()).singleElement().satisfies(l -> {
            assertThat(l.commentId()).isEqualTo(11);
            assertThat(l.kind()).isEqualTo("ANECDOTE");
            assertThat(l.paraphrase()).isTrue();
            assertThat(l.author()).isEqualTo("user11");
        });
        assertThat(r.quotes()).singleElement().satisfies(l -> {
            assertThat(l.paraphrase()).isFalse();
            assertThat(l.kind()).isEqualTo("FACT");
        });
        assertThat(r.dropped()).isZero();
        assertThat(r.repaired()).isZero();
    }

    @Test
    void dropsPointsCitingFabricatedIdsAndRepairsPartlyFabricatedOnes() {
        var r = run(doc("""
                [{"text": "Made-up point.", "kind": "fact", "commentIds": [999]},
                 {"text": "Go is said to be faster to production.", "kind": "opinion", "commentIds": [404, 12]},
                 {"text": "Latency improved for one team.", "kind": "anecdote", "commentIds": [11, 11]}]""", "[]"));
        assertThat(r.summary()).extracting(Line::text)
                .containsExactly("Go is said to be faster to production.", "Latency improved for one team.");
        assertThat(r.summary().getFirst().commentIds()).containsExactly(12L);
        assertThat(r.summary().get(1).commentIds()).containsExactly(11L);
        assertThat(r.dropped()).isEqualTo(1);
        assertThat(r.repaired()).isEqualTo(2);
        assertThat(r.citedIds()).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    void quotesMustBeVerbatimButWhitespaceIsNormalised() {
        var r = run(doc("""
                [{"text": "A point.", "kind": "opinion", "commentIds": [12]}]""", """
                [{"commentId": 12, "quote": "I disagree. Go gets most backend teams to production faster", "kind": "opinion"},
                 {"commentId": 12, "quote": "Go is always faster for every team", "kind": "opinion"},
                 {"commentId": 13, "quote": "\\u201cp99 latency dropped by 40%\\u201d", "kind": "fact"},
                 {"commentId": 11, "quote": "short", "kind": "fact"}]"""));
        // 1: whitespace differs from the comment but the words are verbatim -> kept.
        // 2: paraphrase presented as a quote -> dropped.
        // 3: verbatim, but attributed to the wrong comment -> re-attributed to 11 (the only comment containing it).
        // 4: too short to be a meaningful quote -> dropped.
        assertThat(r.quotes()).extracting(Line::commentId).containsExactly(12L, 11L);
        assertThat(r.quotes().get(1).text()).isEqualTo("p99 latency dropped by 40%");
        assertThat(r.dropped()).isEqualTo(2);
        assertThat(r.repaired()).isEqualTo(1);
    }

    @Test
    void dropsSchemaViolatingItemsAndCapsCounts() {
        var r = run(doc("""
                [{"text": "Valid.", "kind": "opinion", "commentIds": [11]},
                 {"text": "Unknown kind.", "kind": "rumour", "commentIds": [11]},
                 {"text": 42, "kind": "fact", "commentIds": [11]},
                 {"text": "Ids not a list.", "kind": "fact", "commentIds": "11"},
                 {"text": "valid.", "kind": "opinion", "commentIds": [11]},
                 {"text": "", "kind": "fact", "commentIds": [11]},
                 {"text": "Two.", "kind": "fact", "commentIds": [12]},
                 {"text": "Three.", "kind": "fact", "commentIds": [12]},
                 {"text": "Four.", "kind": "fact", "commentIds": [13]},
                 {"text": "Five.", "kind": "fact", "commentIds": [13]},
                 {"text": "Six is over the cap.", "kind": "fact", "commentIds": [13]}]""", "[]"));
        assertThat(r.summary()).extracting(Line::text).containsExactly("Valid.", "Two.", "Three.", "Four.", "Five.");
        assertThat(r.dropped()).isEqualTo(6);
    }

    @Test
    void rejectsOutputThatIsNotTheExpectedObject() {
        assertThatThrownBy(() -> run("Here is your digest: ...")).isInstanceOf(DigestValidator.InvalidOutput.class);
        assertThatThrownBy(() -> run("[1, 2, 3]")).isInstanceOf(DigestValidator.InvalidOutput.class);
        assertThatThrownBy(() -> run("{\"summary\": []}")).isInstanceOf(DigestValidator.InvalidOutput.class)
                .hasMessageContaining("supporting");
        assertThatThrownBy(() -> run(null)).isInstanceOf(DigestValidator.InvalidOutput.class);
    }

    @Test
    void rejectsADigestWhoseEverySummaryPointIsUnverifiable() {
        assertThatThrownBy(() -> run(doc("""
                [{"text": "Invented.", "kind": "fact", "commentIds": [1, 2]}]""", "[]")))
                .isInstanceOf(DigestValidator.InvalidOutput.class)
                .hasMessageContaining("verified");
    }

    @Test
    void trimsWrappingQuotationMarksOnly() {
        assertThat(DigestValidator.trimQuote("  \"“it works”\" ")).isEqualTo("it works");
        assertThat(DigestValidator.trimQuote("it's fine")).isEqualTo("it's fine");
        assertThat(DigestValidator.isVerbatim("a  b\nc", "x a b c y")).isTrue();
        assertThat(DigestValidator.isVerbatim("", "anything")).isFalse();
    }
}
