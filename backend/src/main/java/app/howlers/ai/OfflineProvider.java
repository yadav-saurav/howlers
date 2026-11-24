package app.howlers.ai;

import app.howlers.ai.AiDtos.Source;
import app.howlers.insight.Stance;
import app.howlers.insight.TextSignals;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Retrieval-only answers for when no LLM is configured. It does not "reason": it groups the most relevant
 * sentences from the retrieved discussions by stance and quotes them with citations, and it says clearly that
 * it is not a generated answer. Useful on its own (it's a cited research brief) and keeps the feature usable in
 * local development without a paid API.
 */
@Component
public class OfflineProvider implements AiProvider {
    /** Words that make poor follow-up topics on their own ("disagree about resources"). */
    private static final Set<String> FOLLOW_UP_SKIP = Set.of("help", "resources", "resource", "mistakes", "learning", "learn",
            "tips", "advice", "use", "using", "used", "found", "useful", "common", "best", "worth");

    @Override
    public String id() {
        return "offline";
    }

    @Override
    public String label() {
        return "Offline retrieval (no AI model configured)";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Answer answer(Request r) {
        List<Source> src = r.sources();
        String notice = "No AI model is configured, so this is a cited summary of the most relevant discussions rather than "
                + "a generated answer. Set ANTHROPIC_API_KEY on the server to enable full answers.";
        if (src.isEmpty()) {
            return new Answer("I couldn't find community discussions about that yet. Try different keywords, widen the date "
                    + "range, or ask in a relevant community.", List.of(), notice, null, null);
        }
        Set<String> keywords = new LinkedHashSet<>(QueryAnalyzer.keywords(r.question()));
        List<String> supporting = new ArrayList<>();
        List<String> opposing = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (Source s : src) {
            String best = bestSentence(s.excerpt(), keywords);
            if (best == null) continue;
            String line = "- “" + best + "” [" + s.n() + "]";
            Stance st = TextSignals.suggestStance(s.excerpt());
            if (st == Stance.OPPOSE) opposing.add(line);
            else if (st == Stance.SUPPORT || st == Stance.EXPERIENCE || st == Stance.EVIDENCE) supporting.add(line);
            else other.add(line);
        }
        long communities = src.stream().map(Source::communitySlug).distinct().count();
        long threads = src.stream().map(Source::postId).distinct().count();
        StringBuilder b = new StringBuilder();
        b.append("Found ").append(src.size()).append(" relevant ").append(src.size() == 1 ? "contribution" : "contributions")
                .append(" across ").append(threads).append(threads == 1 ? " thread" : " threads").append(" in ")
                .append(communities).append(communities == 1 ? " community" : " communities").append(". Here is what members said:\n\n");
        appendGroup(b, "What members report or recommend", supporting);
        appendGroup(b, "Pushback and counterpoints", opposing);
        appendGroup(b, "Other relevant points", other);
        if (opposing.isEmpty() && supporting.size() > 1) {
            b.append("\nNo explicit disagreement turned up in these sources. That isn't the same as consensus, because the sample is small.\n");
        }
        if (src.size() < 3) b.append("\nOnly a few sources matched, so treat this as a starting point.\n");
        List<String> topic = keywords.stream().filter(k -> !FOLLOW_UP_SKIP.contains(k)).limit(2).toList();
        List<String> follow = new ArrayList<>();
        Source top = src.getFirst();
        follow.add("Summarise the thread “" + (top.title().length() > 60 ? top.title().substring(0, 59) + "…" : top.title()) + "”");
        if (!topic.isEmpty()) follow.add("Where do members disagree about " + String.join(" and ", topic) + "?");
        return new Answer(b.toString().strip(), follow, notice, null, null);
    }

    private static void appendGroup(StringBuilder b, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        b.append("**").append(title).append("**\n");
        lines.stream().limit(5).forEach(l -> b.append(l).append('\n'));
        b.append('\n');
    }

    /** The sentence sharing the most question keywords (ties: the earlier one). */
    static String bestSentence(String text, Set<String> keywords) {
        List<String> sents = TextSignals.sentences(text);
        if (sents.isEmpty()) {
            String plain = TextSignals.plain(text);
            return plain.length() < 20 ? null : TextSignals.excerpt(plain, 220);
        }
        String best = sents.getFirst();
        int bestScore = -1;
        for (String s : sents) {
            String norm = TextSignals.normalise(s);
            int score = 0;
            for (String k : keywords) if (norm.contains(k)) score++;
            if (score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best.length() > 260 ? best.substring(0, 259) + "…" : best;
    }
}
