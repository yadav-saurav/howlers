package app.howlers.insight;

import app.howlers.insight.InsightDtos.ArgItem;
import app.howlers.insight.InsightDtos.Consensus;
import app.howlers.insight.InsightDtos.Coverage;
import app.howlers.insight.InsightDtos.DigestSections;
import app.howlers.insight.InsightDtos.EvidenceLink;
import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.ThreadLoader.Node;
import app.howlers.insight.ThreadLoader.Snapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Offline digest: selects the most representative real sentences from the thread and attributes each one.
 * It never writes new claims, so it cannot hallucinate. It also reports consensus only when the labelled
 * stances genuinely agree.
 */
@Component
public class ExtractiveDigestEngine implements DigestEngine {

    @Override
    public String id() {
        return "extractive-v2";
    }

    public static final String ID = "extractive-v2";

    @Override
    public String note() {
        return "Built by selecting and quoting real sentences from the most helpful comments. No AI provider is configured, "
                + "so nothing here is paraphrased or invented. Every line links to its source.";
    }

    /** Note used when this engine stands in for the LLM engine (the reason is given separately in the digest's notice). */
    public static String fallbackNote() {
        return "Extractive summary: real sentences quoted from the most helpful comments, nothing paraphrased or invented. "
                + "Every line links to its source.";
    }

    private record Candidate(String text, Node node, String kind, double weight) {}

    @Override
    public DigestSections generate(Snapshot s, String length, String focus) {
        int lines = switch (length) { case "short" -> 2; case "detailed" -> 7; default -> 4; };
        int perSection = perSection(length);
        List<Node> nodes = s.nodes();

        List<Candidate> all = new ArrayList<>();
        for (Node n : nodes) {
            List<String> sents = TextSignals.sentences(n.body());
            for (int i = 0; i < sents.size(); i++) {
                String t = sents.get(i);
                String kind = TextSignals.kind(t);
                all.add(new Candidate(t, n, kind, weight(n, t, kind, i, focus)));
            }
        }
        all.sort(Comparator.comparingDouble(Candidate::weight).reversed());

        List<Line> summary = pick(all, c -> true, lines, focus.equals("balanced") ? 1 : 2);
        List<Line> takeaways = pick(all, c -> c.node().stance() != Stance.QUESTION && !summaryHas(summary, c), perSection + 1, 1);
        List<Line> supporting = pick(all, c -> c.node().stance() == Stance.SUPPORT || c.node().stance() == Stance.EXPERIENCE
                && c.node().parentId() == null, perSection, 1);
        List<Line> opposing = pick(all, c -> c.node().stance() == Stance.OPPOSE, perSection, 1);

        String coverageNote = s.totalLive() > nodes.size()
                ? "Covers the " + nodes.size() + " highest-ranked of " + s.totalLive() + " comments." : null;
        return new DigestSections(summary, question(s.title(), s.postText()), takeaways, supporting, opposing,
                mostUseful(nodes, perSection), minority(nodes), unresolved(nodes, perSection), links(nodes),
                consensus(nodes), List.of(), new Coverage(nodes.size(), s.totalLive(), coverageNote));
    }

    static int perSection(String length) {
        return switch (length) { case "short" -> 2; case "detailed" -> 5; default -> 3; };
    }

    private static final Comparator<Node> HELPFUL = Comparator.comparingDouble(Node::best).reversed()
            .thenComparing(Comparator.comparingInt(Node::score).reversed());

    // The sections below are computed from real data (scores, stances, links) for every engine, never by a model.

    static String question(String title, String postText) {
        return title + (postText == null || postText.isBlank() ? "" : " — " + TextSignals.excerpt(postText, 220));
    }

    static List<ArgItem> mostUseful(List<Node> nodes, int max) {
        return nodes.stream().filter(n -> n.score() > 0).sorted(HELPFUL).limit(max).map(ContextService::item).toList();
    }

    static List<ArgItem> minority(List<Node> nodes) {
        long sup = nodes.stream().filter(n -> n.stance() == Stance.SUPPORT).count();
        long opp = nodes.stream().filter(n -> n.stance() == Stance.OPPOSE).count();
        Stance smaller = sup == 0 || opp == 0 ? null : (sup <= opp ? Stance.SUPPORT : Stance.OPPOSE);
        List<ArgItem> minority = new ArrayList<>();
        if (smaller != null) {
            nodes.stream().filter(n -> n.stance() == smaller).sorted(HELPFUL).limit(2).map(ContextService::item).forEach(minority::add);
        }
        nodes.stream().filter(n -> n.score() <= 0 && TextSignals.plain(n.body()).length() > 180)
                .sorted(HELPFUL).limit(1).map(ContextService::item).forEach(minority::add);
        return minority;
    }

    static List<ArgItem> unresolved(List<Node> nodes, int max) {
        return nodes.stream().filter(n -> n.stance() == Stance.QUESTION && n.replyCount() == 0)
                .sorted(HELPFUL).limit(max).map(ContextService::item).toList();
    }

    static List<EvidenceLink> links(List<Node> nodes) {
        List<EvidenceLink> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node n : nodes) {
            for (String u : TextSignals.urls(n.body())) {
                if (seen.add(u) && links.size() < 10) links.add(new EvidenceLink(u, ContextService.domain(u), n.id(), TextSignals.excerpt(n.body(), 140)));
            }
        }
        return links;
    }

    /**
     * Consensus is only claimed with enough labelled signal and a clear majority. Suggested (rules-based or
     * AI-suggested) stances count at half weight so heuristics alone can't manufacture agreement.
     */
    static Consensus consensus(List<Node> nodes) {
        double sup = 0, opp = 0;
        for (Node n : nodes) {
            double w = n.suggested() ? 0.5 : 1.0;
            if (n.stance() == Stance.SUPPORT) sup += w;
            if (n.stance() == Stance.OPPOSE) opp += w;
        }
        double total = sup + opp;
        if (nodes.size() < 5 || total < 3) {
            return new Consensus("INSUFFICIENT", "Not enough discussion to say whether people agree.");
        }
        double share = Math.max(sup, opp) / total;
        if (share >= 0.75) {
            return new Consensus("CONSENSUS", sup >= opp
                    ? "Most replies agree with the original post (" + Math.round(share * 100) + "% of stance-tagged replies)."
                    : "Most replies push back on the original post (" + Math.round(share * 100) + "% of stance-tagged replies).");
        }
        return new Consensus("DIVIDED", "Opinions are divided: neither side holds a clear majority of replies.");
    }

    private static boolean summaryHas(List<Line> summary, Candidate c) {
        return summary.stream().anyMatch(l -> l.text().equals(c.text()));
    }

    private static List<Line> pick(List<Candidate> sorted, Predicate<Candidate> filter, int max, int perComment) {
        List<Line> out = new ArrayList<>();
        List<Set<String>> chosen = new ArrayList<>();
        java.util.Map<Long, Integer> used = new java.util.HashMap<>();
        for (Candidate c : sorted) {
            if (out.size() >= max) break;
            if (!filter.test(c)) continue;
            if (used.getOrDefault(c.node().id(), 0) >= perComment) continue;
            Set<String> words = words(c.text());
            if (chosen.stream().anyMatch(w -> jaccard(w, words) > 0.5)) continue;
            chosen.add(words);
            used.merge(c.node().id(), 1, Integer::sum);
            out.add(new Line(c.text(), c.node().id(), c.kind(), c.node().author() == null ? null : c.node().author().username()));
        }
        return out;
    }

    private static double weight(Node n, String text, String kind, int position, String focus) {
        double w = n.best() * 3 + Math.log1p(Math.max(n.score(), 0)) * 0.6 - position * 0.15;
        int len = text.length();
        if (len < 50) w -= 0.4;
        if (len > 220) w -= 0.2;
        switch (focus) {
            case "top" -> w += Math.log1p(Math.max(n.score(), 0));
            case "new" -> w += n.createdAt().getEpochSecond() / 86_400.0 * 0.05 - n.best();
            case "technical" -> {
                if (kind.equals("DATA")) w += 0.8;
                if (text.contains("`")) w += 0.5;
            }
            case "beginner" -> {
                double avgWord = Arrays.stream(text.split("\\s+")).mapToInt(String::length).average().orElse(5);
                w += (6 - avgWord) * 0.3;
                if (text.contains("`")) w -= 0.6;
            }
            default -> {
                if (n.stance() == Stance.OPPOSE || n.stance() == Stance.SUPPORT) w += 0.3;
            }
        }
        if (kind.equals("SPECULATION")) w -= 0.2;
        return w;
    }

    private static Set<String> words(String s) {
        return new LinkedHashSet<>(Arrays.asList(TextSignals.normalise(s).split("\\s+")));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }
}
