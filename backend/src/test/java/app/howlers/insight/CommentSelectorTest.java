package app.howlers.insight;

import static app.howlers.insight.InsightFixtures.node;
import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.insight.ThreadLoader.Node;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentSelectorTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String LONG = "A fairly long comment body that costs a predictable number of tokens to send. ".repeat(10);

    /** 100 SUPPORT comments in rank order, one low-ranked OPPOSE (#90), with #100 the newest. */
    private List<Node> ranked() {
        List<Node> out = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Stance st = i == 90 ? Stance.OPPOSE : Stance.SUPPORT;
            out.add(node(i, null, LONG + i, 100 - i, 1.0 - i / 1000.0, st, StanceSource.RULES, T0.plusSeconds(i == 100 ? 100_000 : i)));
        }
        return out;
    }

    @Test
    void everythingFitsWhenTheBudgetAllows() {
        List<Node> all = ranked();
        assertThat(CommentSelector.select(all, 1_000_000)).isEqualTo(all);
    }

    @Test
    void staysWithinBudgetKeepsTheMinoritySideAndTheNewest() {
        List<Node> all = ranked();
        int per = CommentSelector.estimateTokens(all.getFirst());
        int budget = per * 10;
        List<Node> picked = CommentSelector.select(all, budget);
        assertThat(picked.stream().mapToInt(CommentSelector::estimateTokens).sum()).isLessThanOrEqualTo(budget);
        assertThat(picked).hasSizeBetween(8, 10);
        List<Long> ids = picked.stream().map(Node::id).toList();
        assertThat(ids).contains(90L);   // the only opposing comment, despite its low rank
        assertThat(ids).contains(100L);  // the newest comment
        assertThat(ids).contains(1L, 2L, 3L); // top-ranked of the majority stance
        // Returned in rank order, not selection order.
        assertThat(ids).isSorted();
    }

    @Test
    void skipsCommentsThatDontFitButKeepsFillingWithSmallerOnes() {
        List<Node> all = List.of(
                node(1, null, "x".repeat(3000), 10, 0.9, Stance.SUPPORT, StanceSource.RULES, T0),
                node(2, null, "short and sweet comment", 5, 0.5, Stance.SUPPORT, StanceSource.RULES, T0.plusSeconds(1)));
        assertThat(CommentSelector.select(all, 100)).extracting(Node::id).containsExactly(2L);
    }
}
