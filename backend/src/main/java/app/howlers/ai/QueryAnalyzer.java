package app.howlers.ai;

import app.howlers.ai.AiDtos.Intent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a natural-language question into (a) an intent and (b) a safe PostgreSQL tsquery. The tsquery is built
 * only from [a-z0-9] tokens joined with OR and prefix matching, so user input can never inject tsquery syntax,
 * and long questions still match discussions that share only a few key terms.
 */
public final class QueryAnalyzer {
    private QueryAnalyzer() {}

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "of", "to", "in", "on", "for", "with", "about", "as", "at",
            "by", "from", "is", "are", "was", "were", "be", "been", "being", "do", "does", "did", "doing", "have", "has",
            "had", "i", "me", "my", "we", "our", "you", "your", "they", "them", "their", "it", "its", "this", "that",
            "these", "those", "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "can", "could",
            "should", "would", "will", "shall", "may", "might", "must", "any", "some", "all", "most", "more", "much",
            "many", "very", "just", "so", "than", "too", "also", "into", "over", "under", "between", "out", "up", "down",
            "people", "community", "communities", "think", "thinks", "say", "says", "said", "tell", "find", "show",
            "discussions", "discussion", "posts", "post", "comments", "comment", "thread", "threads", "anyone", "someone",
            "recommended", "recommend", "best", "good", "really", "there", "here", "get", "got", "like", "please", "vs",
            "versus", "compared", "compare", "summarize", "summarise", "summary", "opinion", "opinions", "general", "s",
            "developers", "developer", "users", "user", "members", "member", "folks", "things", "thing", "way", "ways",
            "stuff", "lot", "lots", "know", "want", "need", "make", "makes", "made", "repeatedly", "tried", "try", "trying", "help", "helps", "helped", "helpful");

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9+#.-]*");

    public static Intent intent(String q) {
        String s = q.toLowerCase(Locale.ROOT);
        if (s.matches(".*\\b(vs\\.?|versus|compared? (to|with)|difference between|better than)\\b.*")) return Intent.COMPARE;
        if (s.matches(".*\\b(resources?|books?|courses?|tutorials?|links?|reading|roadmap|where (can|do) i learn)\\b.*")) return Intent.RESOURCES;
        if (s.matches(".*\\b(disagree|controvers\\w*|conflict\\w*|debate|argue|arguments?|counter)\\b.*")) return Intent.DEBATE;
        if (s.matches(".*\\b(changed?|trend\\w*|over the (last|past)|lately|recently|this year)\\b.*")) return Intent.TRENDS;
        if (s.matches(".*\\b(summari[sz]e|summary|tl;?dr|overview|recap)\\b.*")) return Intent.SUMMARY;
        return Intent.QUESTION;
    }

    /** Distinct content keywords, in question order (max 12). */
    public static List<String> keywords(String q) {
        Set<String> out = new LinkedHashSet<>();
        var m = TOKEN.matcher(q.toLowerCase(Locale.ROOT));
        while (m.find() && out.size() < 12) {
            String t = m.group().replaceAll("[.\\-]+$", "");
            String norm = t.replaceAll("[^a-z0-9]", "");
            if (norm.length() < 2 || STOP.contains(norm)) continue;
            out.add(norm);
        }
        return new ArrayList<>(out);
    }

    /** "rust | go:*"-style OR query, or null when nothing searchable remains. */
    public static String tsQuery(String q) {
        List<String> k = keywords(q);
        if (k.isEmpty()) return null;
        return String.join(" | ", k.stream().map(t -> t.length() >= 4 ? t + ":*" : t).toList());
    }
}
