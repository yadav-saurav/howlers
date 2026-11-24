package app.howlers.common.text;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Server-side sanitisation of rich-text post bodies. The frontend editor is never trusted:
 * only an allow-list of formatting tags survives, links are forced to http(s)/mailto and get
 * rel="nofollow noopener noreferrer ugc" target="_blank".
 */
@Component
public class HtmlSanitizer {
    private static final Safelist SAFELIST = Safelist.none()
            .addTags("p", "br", "strong", "b", "em", "i", "u", "s", "code", "pre", "blockquote",
                    "ul", "ol", "li", "h2", "h3", "h4", "hr", "a")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer ugc")
            .addEnforcedAttribute("a", "target", "_blank");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings().prettyPrint(false);

    public String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.clean(html, "", SAFELIST, OUTPUT).trim();
    }

    /** Plain text used for previews and full-text search. */
    public String toText(String safeHtml) {
        if (safeHtml == null || safeHtml.isBlank()) return "";
        Document doc = Jsoup.parseBodyFragment(safeHtml);
        doc.select("br").after("\\n");
        doc.select("p, li, h2, h3, h4, blockquote, pre").after("\\n");
        return doc.text().replace("\\n", "\n").replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    public static String preview(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1).trim() + "…";
    }
}
