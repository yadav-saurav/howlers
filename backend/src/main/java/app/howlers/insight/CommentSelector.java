package app.howlers.insight;

import app.howlers.insight.ThreadLoader.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks which comments an AI digest sees when the whole thread doesn't fit the input budget. Order of preference:
 * the best few of every stance (so the minority side is never dropped for being small), the newest few (so recent
 * developments show up), then everything else by rank (best_rank, then score). Comments that don't fit are skipped,
 * and the digest says how many of the thread's comments it covers.
 */
final class CommentSelector {
    private CommentSelector() {}

    static final int PER_STANCE = 3;
    static final int NEWEST = 5;

    /** Rough token estimate for one comment as sent (≈ 4 characters per token plus tag overhead). */
    static int estimateTokens(Node n) {
        return InsightPrompts.commentText(n).length() / 4 + 20;
    }

    /**
     * @param ranked nodes in rank order (as {@link ThreadLoader} returns them)
     * @return the selected nodes, in rank order
     */
    static List<Node> select(List<Node> ranked, int budgetTokens) {
        Set<Node> wanted = new LinkedHashSet<>();
        for (Stance st : Stance.values()) {
            ranked.stream().filter(n -> n.stance() == st).limit(PER_STANCE).forEach(wanted::add);
        }
        ranked.stream().sorted(Comparator.comparing(Node::createdAt).reversed()).limit(NEWEST).forEach(wanted::add);
        wanted.addAll(ranked);

        Set<Long> chosen = new LinkedHashSet<>();
        int used = 0;
        for (Node n : wanted) {
            int cost = estimateTokens(n);
            if (used + cost > budgetTokens) continue;
            used += cost;
            chosen.add(n.id());
        }
        List<Node> out = new ArrayList<>();
        for (Node n : ranked) if (chosen.contains(n.id())) out.add(n);
        return out;
    }
}
