package app.howlers.insight;

import app.howlers.insight.InsightDtos.Coverage;
import app.howlers.insight.InsightDtos.DigestSections;
import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.ThreadLoader.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread Digest written by an LLM (D-031). The model sees a budgeted selection of live comments the requester can
 * view, returns schema-constrained JSON, and {@link DigestValidator} checks every citation and quote against the
 * thread. Consensus, most-useful comments, minority views, unanswered question comments and links are computed
 * from real data exactly as in the extractive engine, never by the model. Any failure is reported as
 * {@link Unavailable} so the caller can fall back to the extractive engine and say so.
 */
@Component
public class LlmDigestEngine {
    private static final Logger log = LoggerFactory.getLogger(LlmDigestEngine.class);

    public static final String ID = "llm-v1";
    /** Input budget for comment text (tokens, estimated). Keeps one digest's cost bounded on huge threads. */
    static final int INPUT_BUDGET_TOKENS = 30_000;
    static final long MAX_OUTPUT_TOKENS = 16_000;

    private final InsightLlm llm;
    private final ObjectMapper json;

    public LlmDigestEngine(InsightLlm llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    public boolean available() {
        return llm.available();
    }

    public String label() {
        return llm.label();
    }

    /** Why the model couldn't produce a digest; tokens are what the failed call still cost (0 if none was made). */
    public static final class Unavailable extends RuntimeException {
        final long inputTokens;
        final long outputTokens;

        Unavailable(String reason, long inputTokens, long outputTokens) {
            super(reason);
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }

    public record Generated(DigestSections sections, int included, int dropped, int repaired, long inputTokens,
                            long outputTokens, long cacheReadTokens, long cacheWriteTokens, String note) {}

    public Generated generate(String title, String postText, List<Node> nodes, int totalLive, String length, String focus) {
        List<Node> sent = CommentSelector.select(nodes, INPUT_BUDGET_TOKENS);
        if (sent.isEmpty()) throw new Unavailable("there were no comments to send", 0, 0);
        InsightLlm.Completion out;
        try {
            out = llm.complete("digest", InsightPrompts.DIGEST_SYSTEM,
                    InsightPrompts.digestUser(title, postText, sent, totalLive, length, focus),
                    InsightPrompts.DIGEST_SCHEMA, MAX_OUTPUT_TOKENS);
        } catch (RuntimeException e) {
            log.warn("LLM digest call failed ({})", e.getClass().getSimpleName());
            throw new Unavailable("the AI model couldn't be reached", 0, 0);
        }
        if (out.refused()) throw new Unavailable("the AI model declined to summarise this thread", out.inputTokens(), out.outputTokens());
        if (out.truncated()) throw new Unavailable("the AI model's output was cut off", out.inputTokens(), out.outputTokens());

        InsightPrompts.Shape shape = InsightPrompts.shape(length);
        DigestValidator.Result v;
        try {
            v = DigestValidator.validate(out.json(), sent, shape, json);
        } catch (DigestValidator.InvalidOutput e) {
            log.warn("LLM digest rejected by validation: {}", e.getMessage());
            throw new Unavailable(e.getMessage(), out.inputTokens(), out.outputTokens());
        }
        int perSection = ExtractiveDigestEngine.perSection(length);
        String coverage = sent.size() < totalLive
                ? "Covers " + sent.size() + " of " + totalLive + " comments, chosen by score, stance diversity and recency."
                : "Covers all " + totalLive + " comments.";
        DigestSections sections = new DigestSections(v.summary(), ExtractiveDigestEngine.question(title, postText), v.quotes(),
                v.supporting(), v.opposing(), ExtractiveDigestEngine.mostUseful(nodes, perSection),
                ExtractiveDigestEngine.minority(nodes), ExtractiveDigestEngine.unresolved(nodes, perSection),
                ExtractiveDigestEngine.links(nodes), ExtractiveDigestEngine.consensus(nodes), v.openQuestions(),
                new Coverage(sent.size(), totalLive, coverage));
        StringBuilder note = new StringBuilder("Summarised by ").append(llm.label())
                .append(". Points are paraphrased and cite the comments they come from; quotes are checked word for word ")
                .append("against the comment. Agreement is computed from stance labels, not by the model.");
        if (v.dropped() > 0) {
            note.append(' ').append(v.dropped()).append(v.dropped() == 1 ? " point was" : " points were")
                    .append(" removed because their sources couldn't be verified.");
        }
        return new Generated(sections, sent.size(), v.dropped(), v.repaired(), out.inputTokens(), out.outputTokens(),
                out.cacheReadTokens(), out.cacheWriteTokens(), note.toString());
    }

    static List<Long> cited(DigestSections s) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (List<Line> l : java.util.Arrays.asList(s.summary(), s.takeaways(), s.supporting(), s.opposing(), s.openQuestions())) {
            if (l != null) l.forEach(x -> ids.addAll(x.sources()));
        }
        return List.copyOf(ids);
    }
}
