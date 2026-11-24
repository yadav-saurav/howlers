package app.howlers.automod;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure matching for automod rules (D-045). Keyword lists and moderator regexes are compiled with RE2/J, which runs in
 * time linear in the input and has no backtracking, so patterns such as {@code (a+)+$} can't blow up. As a second
 * guard, the scanned text is capped at {@link #MAX_INPUT} characters and wrapped in a sequence that aborts the match
 * once a per-rule time budget is spent (a timed-out rule counts as "no match" and is reported, never as a hit).
 */
public final class AutomodMatcher {
    private AutomodMatcher() {}

    public static final int MAX_INPUT = 20_000;
    public static final Duration BUDGET = Duration.ofMillis(50);
    public static final int MAX_REGEX = 500;
    public static final int MAX_LINES = 200;
    public static final int MAX_LINE = 100;

    /** Letters, digits and underscore: what a keyword boundary means, for every script. */
    private static final String WORD = "[\\p{L}\\p{N}_]";

    /** Outcome of one match attempt. {@code text} is the matched fragment (clipped) when {@code matched}. */
    public record Result(boolean matched, String text, boolean timedOut) {
        static final Result NO = new Result(false, null, false);
        static final Result TIMEOUT = new Result(false, null, true);
    }

    // ------------------------------------------------------------------ compile

    /**
     * One phrase per line, matched case-insensitively as whole words. {@code *} inside a phrase matches any run of
     * letters or digits ({@code spam*} matches "spammer"); everything else is literal.
     */
    public static Pattern keywords(String lines) {
        List<String> parts = new ArrayList<>();
        for (String line : lines(lines)) {
            if (line.replace("*", "").isBlank()) throw new IllegalArgumentException("A keyword can't be only wildcards");
            if (line.length() > MAX_LINE) throw new IllegalArgumentException("Keywords can be up to " + MAX_LINE + " characters");
            StringBuilder sb = new StringBuilder();
            String[] pieces = line.split("\\*", -1);
            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) sb.append(WORD).append('*');
                if (!pieces[i].isEmpty()) sb.append(Pattern.quote(pieces[i]));
            }
            parts.add(sb.toString());
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Add at least one keyword");
        return Pattern.compile("(?:^|[^\\p{L}\\p{N}_])(" + String.join("|", parts) + ")(?:$|[^\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE);
    }

    /** A moderator-written RE2 pattern (no backreferences or lookaround, which RE2 doesn't support), case-insensitive. */
    public static Pattern regex(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Write a pattern");
        if (source.length() > MAX_REGEX) throw new IllegalArgumentException("Patterns can be up to " + MAX_REGEX + " characters");
        try {
            return Pattern.compile(source, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("That pattern isn't valid RE2 syntax (backreferences and lookarounds aren't supported): "
                    + e.getDescription());
        }
    }

    /** Normalised domains, one per line ({@code https://www.Example.com/} becomes {@code example.com}). */
    public static Set<String> domains(String lines) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines(lines)) {
            String d = line.toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "").replaceFirst("^www\\.", "")
                    .replaceFirst("[/?#].*$", "").replaceFirst(":\\d+$", "");
            if (!d.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) throw new IllegalArgumentException("“" + line + "” isn't a domain");
            out.add(d);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Add at least one domain");
        return out;
    }

    static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String l : raw.split("\\R")) {
            String s = l.strip();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.size() > MAX_LINES) throw new IllegalArgumentException("Use up to " + MAX_LINES + " lines");
        return out;
    }

    // ------------------------------------------------------------------ match

    public static Result find(Pattern p, String text) {
        return find(p, text, BUDGET);
    }

    public static Result find(Pattern p, String text, Duration budget) {
        if (text == null || text.isEmpty()) return Result.NO;
        String input = text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;
        try {
            Matcher m = p.matcher(new Deadline(input, System.nanoTime() + budget.toNanos()));
            if (!m.find()) return Result.NO;
            String hit = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            return new Result(true, clip(hit == null ? "" : hit.strip(), 80), false);
        } catch (Timeout e) {
            return Result.TIMEOUT;
        }
    }

    private static final java.util.regex.Pattern URL_HOST =
            java.util.regex.Pattern.compile("(?i)\\bhttps?://([a-z0-9.-]+)");

    /** Hosts of every http(s) URL in the text (also finds them inside {@code href="..."}), lower-case, no www. */
    public static Set<String> hosts(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        java.util.regex.Matcher m = URL_HOST.matcher(text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceFirst("\\.$", ""));
        return out;
    }

    /** The first host that is, or is under, a blocked domain. */
    public static String blockedHost(Set<String> hosts, Set<String> blocked) {
        for (String h : hosts) {
            for (String d : blocked) if (h.equals(d) || h.endsWith("." + d)) return h;
        }
        return null;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static final class Timeout extends RuntimeException {
        Timeout() {
            super(null, null, false, false);
        }
    }

    /** A CharSequence that throws once its deadline passes, checked every 256 reads to keep it cheap. */
    private static final class Deadline implements CharSequence {
        private final String s;
        private final long deadline;
        private int reads;

        Deadline(String s, long deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return s.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFF) == 0 && System.nanoTime() > deadline) throw new Timeout();
            return s.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return s.substring(start, end);
        }

        @Override
        public String toString() {
            return s;
        }
    }
}
