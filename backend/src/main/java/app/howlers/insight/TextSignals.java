package app.howlers.insight;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transparent, rules-based text signals used when no AI provider is configured (and as cheap features
 * when one is). Every rule here is deliberately simple and explainable; outputs are always presented as
 * "suggested" and are user-correctable.
 */
public final class TextSignals {
    private TextSignals() {}

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]>\"']+", Pattern.CASE_INSENSITIVE);
    // (?![A-Za-z0-9]) instead of \b: "\b" never matches after "%" followed by a space.
    private static final Pattern NUMBER_FACT = Pattern.compile(
            "(\\b\\d+(?:[.,]\\d+)?\\s*(?:%|ms|x|k|gb|mb|seconds?|minutes?|months?|weeks?|years?|hours?|days?|users?|rows?|services?|percent)(?![A-Za-z0-9]))|\\bp9[059]\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_PERSON_PAST = Pattern.compile(
            "\\b(i|we|my|our)\\b[^.!?]{0,60}\\b(was|were|had|did|used|tried|ran|shipped|saw|found|learned|spent|got|worked|built|moved|switched|hired|ended)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AGREE = Pattern.compile(
            "^(\\s*(strong(ly)? agree|agree|same here|same|this|exactly|\\+1|seconded|yes|true|so true|100%))|\\b(i agree|agreed|strong(ly)? agree|well said|great point|good point|this matches|matches (ours|mine|my experience)|same experience|spot on|can confirm|saving this|respect for)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISAGREE = Pattern.compile(
            "^(\\s*(counterpoint|disagree|not really|nope|no,|i'd push back|respectfully))|\\b(i disagree|not true|that's wrong|that is wrong|i don't think|i do not think|on the contrary|counterpoint|push back|isn't the case|is not the case|overrated|doesn't hold|not convinced|i'd argue|that ignores|not necessarily)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEDGE = Pattern.compile("\\b(might|may|could|probably|likely|i suspect|i bet|maybe|perhaps|will probably|in the future)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPINION = Pattern.compile("\\b(i think|i believe|in my opinion|imo|imho|should|better|worse|best|worst|overrated|underrated|prefer)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Sentence boundary = terminal punctuation followed by whitespace and a plausible sentence start. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z@\"“(\\d])|\\n+");
    /** Bold/italic markers, inline code ticks, quote/heading markers; links keep their label. Single underscores stay (usernames). */
    private static final Pattern MARKDOWN = Pattern.compile("\\*\\*|__|[*`]|(?m)^\\s*[>#]+|\\[([^\\]]*)]\\([^)]*\\)");

    /** Rule order matters: explicit agreement/disagreement beats incidental features like a link. */
    public static Stance suggestStance(String body) {
        String t = plain(body);
        String first = t.length() > 160 ? t.substring(0, 160) : t;
        String firstSentence = SENTENCE_BREAK.split(t, 2)[0].trim();
        if (DISAGREE.matcher(first).find()) return Stance.OPPOSE;
        if (AGREE.matcher(first).find()) return Stance.SUPPORT;
        if (!URL.matcher(body == null ? "" : body).find() && t.trim().endsWith("?") && t.length() < 400) return Stance.QUESTION;
        if (firstSentence.endsWith("?")) return Stance.QUESTION;
        if (URL.matcher(body == null ? "" : body).find() || NUMBER_FACT.matcher(t).find()) return Stance.EVIDENCE;
        if (FIRST_PERSON_PAST.matcher(t).find()) return Stance.EXPERIENCE;
        if (t.contains("?")) return Stance.QUESTION;
        return Stance.NEUTRAL;
    }

    /** What kind of statement a sentence is. Shown next to digest lines so readers can weigh them. */
    public static String kind(String sentence) {
        if (URL.matcher(sentence).find() || NUMBER_FACT.matcher(sentence).find()) return "DATA";
        if (FIRST_PERSON_PAST.matcher(sentence).find()) return "ANECDOTE";
        if (HEDGE.matcher(sentence).find()) return "SPECULATION";
        if (OPINION.matcher(sentence).find()) return "OPINION";
        return "STATEMENT";
    }

    public static List<String> urls(String body) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(body == null ? "" : body);
        while (m.find()) out.add(m.group().replaceAll("[.,;:]+$", ""));
        return List.copyOf(out);
    }

    public static List<String> sentences(String body) {
        List<String> out = new ArrayList<>();
        String raw = body == null ? "" : MARKDOWN.matcher(body).replaceAll("$1");
        for (String part : SENTENCE_BREAK.split(raw)) {
            String s = part.replaceAll("\\s+", " ").trim();
            if (s.length() >= 25 && s.length() <= 320) out.add(s);
        }
        return out;
    }

    /** Markdown stripped to readable text (links keep their label). */
    public static String plain(String body) {
        if (body == null) return "";
        return MARKDOWN.matcher(body).replaceAll("$1").replaceAll("\\s+", " ").trim();
    }

    public static String excerpt(String body, int max) {
        String t = plain(body);
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    public static boolean isQuestion(String body) {
        return plain(body).trim().endsWith("?");
    }

    public static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
    }
}
