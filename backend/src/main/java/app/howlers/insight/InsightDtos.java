package app.howlers.insight;

import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class InsightDtos {
    private InsightDtos() {}

    /**
     * A comment as an argument. {@code suggested=true} means the stance came from rules or the AI model, not people;
     * {@code stanceSource} says which (READERS, AI or RULES).
     */
    public record ArgItem(long commentId, Long parentId, String excerpt, UserSummary author, int score, Stance stance,
                          boolean suggested, Stance myLabel, Map<Stance, Integer> labelCounts, StanceSource stanceSource) {}

    public record EvidenceLink(String url, String domain, long commentId, String excerpt) {}

    public record MapNode(long id, Long parentId, Stance stance, boolean suggested, int score, String label,
                          StanceSource stanceSource) {}

    public record ContextStats(int analysed, int total, int labelled, int suggested, int participants,
                               Map<Stance, Integer> byStance, int aiSuggested) {}

    /**
     * State of AI stance suggestions for this thread. state: OFF (no model configured), PENDING (a batch is running;
     * poll again), PARTIAL (some unlabelled comments still use rules), READY (every unlabelled analysed comment has an
     * AI suggestion).
     */
    public record AiStances(String state, String engine, int suggested, int remaining, String note) {}

    public record ContextView(long postId, String title, String excerpt, ContextStats stats,
                              Map<Stance, List<ArgItem>> groups, List<EvidenceLink> evidence, List<ArgItem> openQuestions,
                              List<ArgItem> agreement, List<ArgItem> disagreement, List<MapNode> map, boolean truncated,
                              AiStances aiStances) {}

    public record StanceRequest(@NotNull Stance stance) {}

    // ------------------------------------------------------------------ digest

    /**
     * One digest line, always attributed. kind = FACT/DATA/ANECDOTE/OPINION/SPECULATION/STATEMENT. {@code commentId}
     * is the primary source; {@code commentIds} lists every cited comment (null in digests cached before V14).
     * {@code paraphrase=true} means the text is the model's wording (not a quote); false means it is verbatim.
     */
    public record Line(String text, long commentId, String kind, String author, List<Long> commentIds, boolean paraphrase) {
        public Line(String text, long commentId, String kind, String author) {
            this(text, commentId, kind, author, List.of(commentId), false);
        }

        public List<Long> sources() {
            return commentIds == null || commentIds.isEmpty() ? List.of(commentId) : commentIds;
        }
    }

    public record Consensus(String status, String statement) {}

    /** How much of the thread the digest is based on. */
    public record Coverage(int included, int total, String note) {}

    public record DigestSections(List<Line> summary, String question, List<Line> takeaways, List<Line> supporting,
                                 List<Line> opposing, List<ArgItem> mostUseful, List<ArgItem> minority,
                                 List<ArgItem> unresolved, List<EvidenceLink> links, Consensus consensus,
                                 List<Line> openQuestions, Coverage coverage) {}

    /**
     * {@code engine} is "llm-v1" or "extractive-v2". {@code notice} is set when the preferred engine could not be
     * used (e.g. the model call failed, so this is the extractive fallback) and says why.
     */
    public record DigestView(long postId, String length, String focus, String engine, String engineNote,
                             Instant generatedAt, int commentCountAtGeneration, int currentCommentCount, boolean stale,
                             DigestSections sections, String notice) {}
}
