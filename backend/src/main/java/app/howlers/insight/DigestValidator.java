package app.howlers.insight;

import app.howlers.insight.InsightDtos.Line;
import app.howlers.insight.ThreadLoader.Node;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks the model's digest output against the thread before anything is stored or shown (D-031). The schema guarantees
 * shape, not truth, so:
 * <ul>
 *   <li>output that isn't the expected JSON object at all is rejected (the caller falls back to extractive);</li>
 *   <li>a point keeps only cited ids that were actually sent to the model; a point left with none is dropped;</li>
 *   <li>a quote must be a verbatim substring (whitespace-normalised) of the comment it cites. A quote found verbatim
 *       in exactly one other sent comment is re-attributed to it; anything else is dropped;</li>
 *   <li>items with the wrong types, unknown kinds, empty or overlong text are dropped, and counts are capped.</li>
 * </ul>
 */
final class DigestValidator {
    private DigestValidator() {}

    static final int MAX_POINT_CHARS = 400;
    static final int MIN_QUOTE_CHARS = 12;
    static final int MAX_QUOTE_CHARS = 400;

    /** Thrown when the output can't be used at all. */
    static final class InvalidOutput extends RuntimeException {
        InvalidOutput(String message) {
            super(message);
        }
    }

    record Result(List<Line> summary, List<Line> supporting, List<Line> opposing, List<Line> quotes,
                  List<Line> openQuestions, int dropped, int repaired) {
        Set<Long> citedIds() {
            Set<Long> ids = new LinkedHashSet<>();
            for (List<Line> l : List.of(summary, supporting, opposing, quotes, openQuestions)) {
                l.forEach(x -> ids.addAll(x.sources()));
            }
            return ids;
        }
    }

    private static final class Counter {
        int dropped;
        int repaired;
    }

    static Result validate(String json, List<Node> sent, InsightPrompts.Shape shape, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new InvalidOutput("the model's output was not valid JSON");
        }
        if (root == null || !root.isObject()) throw new InvalidOutput("the model's output was not a JSON object");
        for (String f : List.of("summary", "supporting", "opposing", "quotes", "openQuestions")) {
            if (!root.path(f).isArray()) throw new InvalidOutput("the model's output was missing \"" + f + "\"");
        }
        Map<Long, Node> byId = new java.util.HashMap<>();
        for (Node n : sent) byId.put(n.id(), n);
        Counter c = new Counter();
        List<Line> summary = points(root.get("summary"), byId, shape.summary(), true, c);
        List<Line> supporting = points(root.get("supporting"), byId, shape.sides(), true, c);
        List<Line> opposing = points(root.get("opposing"), byId, shape.sides(), true, c);
        List<Line> quotes = quotes(root.get("quotes"), byId, sent, shape.quotes(), c);
        List<Line> questions = points(root.get("openQuestions"), byId, shape.questions(), false, c);
        if (summary.isEmpty()) throw new InvalidOutput("none of the model's summary points could be verified against the thread");
        return new Result(summary, supporting, opposing, quotes, questions, c.dropped, c.repaired);
    }

    private static List<Line> points(JsonNode arr, Map<Long, Node> byId, int max, boolean needKind, Counter c) {
        List<Line> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : arr) {
            if (out.size() >= max) {
                c.dropped++;
                continue;
            }
            String text = p.path("text").isTextual() ? p.get("text").asText().strip() : "";
            String kind = needKind ? kind(p.path("kind")) : "STATEMENT";
            if (text.isEmpty() || text.length() > MAX_POINT_CHARS || kind == null || !p.path("commentIds").isArray()
                    || !seen.add(text.toLowerCase(Locale.ROOT))) {
                c.dropped++;
                continue;
            }
            List<Long> ids = new ArrayList<>();
            int claimed = 0;
            for (JsonNode id : p.get("commentIds")) {
                claimed++;
                if (id.canConvertToLong() && id.isIntegralNumber() && byId.containsKey(id.asLong()) && !ids.contains(id.asLong())) {
                    ids.add(id.asLong());
                }
            }
            if (ids.isEmpty()) {
                c.dropped++;
                continue;
            }
            if (ids.size() < claimed) c.repaired++;
            Node first = byId.get(ids.getFirst());
            out.add(new Line(text, first.id(), kind, username(first), List.copyOf(ids), true));
        }
        return out;
    }

    private static List<Line> quotes(JsonNode arr, Map<Long, Node> byId, List<Node> sent, int max, Counter c) {
        List<Line> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode q : arr) {
            if (out.size() >= max) {
                c.dropped++;
                continue;
            }
            String quote = q.path("quote").isTextual() ? trimQuote(q.get("quote").asText()) : "";
            String kind = kind(q.path("kind"));
            JsonNode idNode = q.path("commentId");
            if (quote.length() < MIN_QUOTE_CHARS || quote.length() > MAX_QUOTE_CHARS || kind == null
                    || !idNode.isIntegralNumber() || !seen.add(squash(quote))) {
                c.dropped++;
                continue;
            }
            Node cited = byId.get(idNode.asLong());
            if (cited == null || !isVerbatim(quote, InsightPrompts.commentText(cited))) {
                List<Node> holders = sent.stream().filter(n -> isVerbatim(quote, InsightPrompts.commentText(n))).toList();
                if (holders.size() != 1) {
                    c.dropped++;
                    continue;
                }
                cited = holders.getFirst();
                c.repaired++;
            }
            out.add(new Line(quote, cited.id(), kind, username(cited), List.of(cited.id()), false));
        }
        return out;
    }

    /** Verbatim after collapsing whitespace on both sides (line breaks in a comment don't break a quote). */
    static boolean isVerbatim(String quote, String source) {
        String q = squash(quote);
        return !q.isEmpty() && squash(source).contains(q);
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    /** Strips wrapping quotation marks the model may add around a quote; never touches the inner text. */
    static String trimQuote(String s) {
        String t = s.strip();
        while (t.length() >= 2 && "\"“”'‘’".indexOf(t.charAt(0)) >= 0 && "\"“”'‘’".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(1, t.length() - 1).strip();
        }
        return t;
    }

    private static String kind(JsonNode k) {
        if (!k.isTextual()) return null;
        return switch (k.asText().toLowerCase(Locale.ROOT)) {
            case "fact" -> "FACT";
            case "opinion" -> "OPINION";
            case "anecdote" -> "ANECDOTE";
            case "speculation" -> "SPECULATION";
            default -> null;
        };
    }

    private static String username(Node n) {
        return n.author() == null ? null : n.author().username();
    }
}
