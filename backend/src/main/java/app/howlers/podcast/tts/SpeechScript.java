package app.howlers.podcast.tts;

import app.howlers.podcast.tts.TtsProvider.Speaker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a two-host episode script into what gets spoken: speaker turns, grouped by section, cleaned for speech and cut
 * into chunks no longer than the provider's per-request limit.
 *
 * <p>The script format is the one {@code ScriptDrafter} asks the AI model for (and moderators can write by hand):
 * <pre>
 * # Title
 * Summary paragraph.
 * ## Outline
 * 1. ...
 * ## Segment title (≈02:30)
 * **HOST A:** spoken line
 * **HOST B:** spoken line
 * ---
 * ## Sources
 * </pre>
 * Only {@code HOST A}/{@code HOST B} lines are spoken (plus continuation lines directly below a turn). The title,
 * summary, outline, notes and the source list are not. Citations like {@code [3]}, Markdown emphasis and URLs are
 * removed from the spoken text.
 */
public final class SpeechScript {
    private SpeechScript() {}

    public record Turn(Speaker speaker, String text, int section) {}

    /** A section heading that has at least one spoken turn: these become chapters. */
    public record Section(int index, String title) {}

    public record Parsed(List<Turn> turns, List<Section> sections) {
        public int characters() {
            return turns.stream().mapToInt(t -> t.text().length()).sum();
        }
    }

    /** One synthesis request. {@code firstOfTurn} marks where a pause goes before it (except at the very start). */
    public record Chunk(int turn, Speaker speaker, String text, int section, boolean firstOfTurn) {}

    private static final Pattern TURN = Pattern.compile("^\\s*(?:[-*]\\s+)?\\**\\s*HOST\\s+([AB])\\s*\\**\\s*:\\s*\\**\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.*?)\\s*$");
    private static final Pattern MINUTE_MARK = Pattern.compile("\\s*[(\\[]\\s*[≈~]?\\s*\\d{1,3}:\\d{2}(?::\\d{2})?\\s*[)\\]]\\s*$");
    private static final Pattern CITATION = Pattern.compile("\\s*\\[\\d{1,3}](?:\\s*\\[\\d{1,3}])*");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]{1,300})]\\((?:[^)\\s]{1,2000})\\)");
    private static final Pattern URL = Pattern.compile("\\bhttps?://\\S+");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*{1,3}|`+|~~)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])[\"'”’)\\]]*\\s+");

    public static Parsed parse(String script) {
        List<Turn> turns = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        if (script == null || script.isBlank()) return new Parsed(turns, sections);
        String pendingTitle = null;
        int section = -1;
        boolean sectionHasTurn = false;
        boolean skipping = false; // inside ## Outline
        StringBuilder current = null;
        Speaker currentSpeaker = null;
        for (String raw : script.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.strip();
            Matcher h = HEADING.matcher(line);
            Matcher t = TURN.matcher(line);
            if (h.matches()) {
                flush(turns, current, currentSpeaker, section);
                current = null;
                String title = MINUTE_MARK.matcher(h.group(2)).replaceAll("").replace("*", "").strip();
                if (h.group(1).length() == 1) continue; // episode title
                String lower = title.toLowerCase();
                if (lower.equals("sources") || lower.equals("references")) break;
                skipping = lower.equals("outline");
                if (!skipping && h.group(1).length() == 2) {
                    pendingTitle = title.isEmpty() ? "Segment" : title;
                    sectionHasTurn = false;
                }
                continue;
            }
            if (skipping) continue;
            if (t.matches()) {
                flush(turns, current, currentSpeaker, section);
                if (pendingTitle != null && !sectionHasTurn) {
                    section = sections.size();
                    sections.add(new Section(section, pendingTitle.length() > 200 ? pendingTitle.substring(0, 200) : pendingTitle));
                    sectionHasTurn = true;
                    pendingTitle = null;
                }
                currentSpeaker = t.group(1).equalsIgnoreCase("A") ? Speaker.A : Speaker.B;
                current = new StringBuilder(t.group(2));
                continue;
            }
            if (line.isEmpty() || line.equals("---") || line.startsWith(">")) {
                flush(turns, current, currentSpeaker, section);
                current = null;
                continue;
            }
            if (current != null) current.append(' ').append(line); // continuation of a turn
        }
        flush(turns, current, currentSpeaker, section);
        return new Parsed(List.copyOf(turns), List.copyOf(sections));
    }

    private static void flush(List<Turn> turns, StringBuilder text, Speaker speaker, int section) {
        if (text == null) return;
        String spoken = clean(text.toString());
        if (!spoken.isEmpty()) turns.add(new Turn(speaker, spoken, section));
    }

    /** What the voice reads: no citations, links, URLs or Markdown emphasis. */
    public static String clean(String s) {
        String out = MD_LINK.matcher(s).replaceAll("$1");
        out = CITATION.matcher(out).replaceAll("");
        out = URL.matcher(out).replaceAll("");
        out = EMPHASIS.matcher(out).replaceAll("");
        out = out.replaceAll("\\s+([,.;:!?])", "$1").replaceAll("\\s{2,}", " ");
        return out.strip();
    }

    /** Splits every turn into chunks of at most {@code max} characters, at sentence boundaries where possible. */
    public static List<Chunk> chunk(List<Turn> turns, int max) {
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            boolean first = true;
            for (String part : split(t.text(), max)) {
                out.add(new Chunk(i, t.speaker(), part, t.section(), first));
                first = false;
            }
        }
        return out;
    }

    /** Greedy packing of sentences; a sentence longer than {@code max} is cut at spaces, a word longer than that hard. */
    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text.length() <= max) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String sentence : SENTENCE_END.split(text)) {
            for (String piece : sentence.length() <= max ? List.of(sentence) : words(sentence, max)) {
                if (cur.length() > 0 && cur.length() + 1 + piece.length() > max) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append(' ');
                cur.append(piece);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static List<String> words(String sentence, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : sentence.split("\\s+")) {
            while (w.length() > max) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(w.substring(0, max));
                w = w.substring(max);
            }
            if (cur.length() > 0 && cur.length() + 1 + w.length() > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public static String label(Speaker s) {
        return s == Speaker.A ? "Host A" : "Host B";
    }
}
