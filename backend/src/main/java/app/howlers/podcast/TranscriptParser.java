package app.howlers.podcast;

import app.howlers.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses WebVTT, SRT and simple timestamped text ("[00:01:23] Host: text") into timed segments, and renders segments
 * back as WebVTT for the player's subtitle track. Timing always comes from the input: nothing is estimated.
 */
public final class TranscriptParser {
    private TranscriptParser() {}

    public record Segment(double start, double end, String speaker, String text) {}

    private static final Pattern CUE = Pattern.compile(
            "^\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}).*$");
    private static final Pattern STAMP = Pattern.compile("^\\s*\\[((?:\\d+:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?)]\\s*(.*)$");
    private static final Pattern VOICE = Pattern.compile("^<v(?:\\.[^ >]*)?\\s+([^>]{1,80})>(.*)$");
    private static final Pattern SPEAKER = Pattern.compile("^([A-Z][\\w .'-]{0,40}):\\s+(.+)$");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    public static final int MAX_SEGMENTS = 20_000;

    public static List<Segment> parse(String format, String content, Integer durationSeconds) {
        if (content == null || content.isBlank()) throw ApiException.field("content", "The transcript is empty");
        if (content.length() > 2_000_000) throw ApiException.field("content", "The transcript is too large");
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').replace("﻿", "");
        List<Segment> out = switch (format.toLowerCase(Locale.ROOT)) {
            case "vtt", "srt" -> cues(normalized);
            case "timestamped" -> stamped(normalized, durationSeconds);
            default -> throw ApiException.field("format", "Use vtt, srt or timestamped");
        };
        if (out.isEmpty()) throw ApiException.field("content", "No timed lines were found. Check the format");
        if (out.size() > MAX_SEGMENTS) throw ApiException.field("content", "Too many transcript segments");
        return out;
    }

    private static List<Segment> cues(String s) {
        List<Segment> out = new ArrayList<>();
        String[] lines = s.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CUE.matcher(lines[i]);
            if (!m.matches()) continue;
            double start = seconds(m.group(1)), end = seconds(m.group(2));
            StringBuilder text = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].isBlank()) {
                if (!text.isEmpty()) text.append(' ');
                text.append(lines[j].strip());
                j++;
            }
            i = j;
            if (text.isEmpty() || end < start) continue;
            out.add(withSpeaker(start, end, text.toString()));
        }
        return out;
    }

    private static List<Segment> stamped(String s, Integer duration) {
        record Raw(double start, String text) {}
        List<Raw> raw = new ArrayList<>();
        for (String line : s.split("\n")) {
            Matcher m = STAMP.matcher(line);
            if (m.matches() && !m.group(2).isBlank()) raw.add(new Raw(seconds(m.group(1)), m.group(2).strip()));
            else if (!line.isBlank() && !raw.isEmpty()) {
                Raw last = raw.removeLast(); // continuation line
                raw.add(new Raw(last.start(), last.text() + " " + line.strip()));
            }
        }
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            double start = raw.get(i).start();
            double end = i + 1 < raw.size() ? raw.get(i + 1).start() : (duration != null && duration > start ? duration : start + 10);
            if (end < start) throw ApiException.field("content", "Timestamps must be in order");
            out.add(withSpeaker(start, end, raw.get(i).text()));
        }
        return out;
    }

    private static Segment withSpeaker(double start, double end, String text) {
        Matcher v = VOICE.matcher(text);
        if (v.matches()) return new Segment(start, end, v.group(1).strip(), clean(v.group(2)));
        String cleaned = clean(text);
        Matcher sp = SPEAKER.matcher(cleaned);
        if (sp.matches()) return new Segment(start, end, sp.group(1).strip(), sp.group(2).strip());
        return new Segment(start, end, null, cleaned);
    }

    private static String clean(String t) {
        return TAGS.matcher(t).replaceAll("").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").strip();
    }

    /** "1:02:03.5", "02:03,250" or "2:03" → seconds. */
    static double seconds(String t) {
        String[] parts = t.replace(',', '.').split(":");
        double s = 0;
        for (String p : parts) s = s * 60 + Double.parseDouble(p);
        return s;
    }

    public static String toVtt(List<Segment> segments) {
        StringBuilder b = new StringBuilder("WEBVTT\n\n");
        int n = 1;
        for (Segment s : segments) {
            b.append(n++).append('\n').append(stamp(s.start())).append(" --> ").append(stamp(s.end())).append('\n');
            if (s.speaker() != null) b.append("<v ").append(escape(s.speaker())).append('>');
            b.append(escape(s.text())).append("\n\n");
        }
        return b.toString();
    }

    private static String escape(String s) {
        // "-->" would end a cue early, so swap it for an arrow before escaping its ">".
        return s.replace("-->", "→").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String stamp(double sec) {
        long ms = Math.round(sec * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }
}
