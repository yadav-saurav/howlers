package app.howlers.common.text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts @username mentions (bounded, de-duplicated, case-insensitive). */
public final class Mentions {
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_/@])@([A-Za-z0-9_]{3,21})\\b");

    private Mentions() {}

    public static List<String> extract(String text, int max) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(text);
        while (m.find() && out.size() < max) out.add(m.group(1).toLowerCase());
        return List.copyOf(out);
    }
}
