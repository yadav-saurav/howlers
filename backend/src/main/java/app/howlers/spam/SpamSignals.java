package app.howlers.spam;

import app.howlers.common.text.Mentions;
import app.howlers.common.text.Tokens;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, cheap text signals computed at write time. No I/O, so they are unit-tested directly. */
public final class SpamSignals {
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"')]+");
    private static final Pattern HREF = Pattern.compile("(?i)href\\s*=\\s*[\"']https?://");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SpamSignals() {}

    /** Lower-case, punctuation and whitespace collapsed, so trivial edits ("Buy now!!" vs "buy now") hash the same. */
    public static String normalize(String s) {
        if (s == null) return "";
        return NON_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public static String contentHash(String title, String bodyText) {
        return Tokens.sha256(normalize(title) + "\n" + normalize(bodyText));
    }

    /** Scheme, "www.", fragment and trailing slashes are ignored; the path and query are kept. */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url.strip());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return host + path + query;
        } catch (IllegalArgumentException e) {
            return url.strip().toLowerCase(Locale.ROOT);
        }
    }

    public static String linkHash(String url) {
        String n = normalizeUrl(url);
        return n == null ? null : Tokens.sha256(n);
    }

    /** Links in plain text (comments are Markdown, so a URL is a URL). */
    public static int linksInText(String text) {
        if (text == null || text.isEmpty()) return 0;
        Matcher m = URL.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Links in sanitized post HTML (anchors), plus the post's own link. */
    public static int linksInPost(String html, String url) {
        int n = url == null ? 0 : 1;
        if (html == null || html.isEmpty()) return n;
        Matcher m = HREF.matcher(html);
        while (m.find()) n++;
        return n;
    }

    public static int mentionCount(String text) {
        return Mentions.extract(text, 1000).size();
    }
}
