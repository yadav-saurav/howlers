package app.howlers.seo;

import app.howlers.common.error.ApiException;
import app.howlers.seo.SeoService.PageMeta;
import app.howlers.seo.SeoService.SitemapUrl;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crawler-facing documents (D-039). nginx serves {@code /robots.txt}, {@code /sitemap.xml} and {@code /sitemaps/*}
 * from here, and sends page requests from link-preview bots and search crawlers to {@code /page}, passing the
 * original request URI in {@code X-Howlers-Path}.
 */
@Tag(name = "SEO")
@RestController
@RequestMapping("/api/v1/seo")
public class SeoController {
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");
    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");
    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final Pattern SITEMAP = Pattern.compile("^(static|communities|posts|podcasts)-(\\d{1,4})$");

    private final SeoService seo;

    public SeoController(SeoService seo) {
        this.seo = seo;
    }

    /** A minimal HTML document with the page's title, description, canonical link, Open Graph and Twitter tags. */
    @GetMapping("/page")
    public ResponseEntity<String> page(@RequestParam(required = false) String path,
                                       @RequestHeader(name = "X-Howlers-Path", required = false) String original) {
        PageMeta m = seo.resolve(original != null && !original.isBlank() ? original : path == null ? "/" : path);
        return ResponseEntity.ok().contentType(HTML)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("Vary", "X-Howlers-Path")
                .body(render(m));
    }

    static String render(PageMeta m) {
        StringBuilder b = new StringBuilder(2048);
        b.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        b.append("<title>").append(esc(m.title())).append("</title>\n");
        meta(b, "name", "description", m.description());
        if (m.noindex()) meta(b, "name", "robots", "noindex");
        b.append("<link rel=\"canonical\" href=\"").append(esc(m.canonical())).append("\">\n");
        meta(b, "property", "og:site_name", "Howlers");
        meta(b, "property", "og:type", m.type());
        meta(b, "property", "og:title", m.title());
        meta(b, "property", "og:description", m.description());
        meta(b, "property", "og:url", m.canonical());
        meta(b, "property", "og:image", m.image());
        meta(b, "property", "og:image:type", "image/png");
        meta(b, "property", "og:image:width", "1200");
        meta(b, "property", "og:image:height", "630");
        meta(b, "property", "og:image:alt", m.imageAlt());
        if (m.published() != null) {
            meta(b, "property", "article:published_time", DateTimeFormatter.ISO_INSTANT.format(m.published()));
        }
        meta(b, "name", "twitter:card", "summary_large_image");
        meta(b, "name", "twitter:title", m.title());
        meta(b, "name", "twitter:description", m.description());
        meta(b, "name", "twitter:image", m.image());
        meta(b, "name", "twitter:image:alt", m.imageAlt());
        b.append("</head><body>\n<main>\n<h1>").append(esc(m.title())).append("</h1>\n");
        if (m.context() != null) b.append("<p>").append(esc(m.context())).append("</p>\n");
        b.append("<p>").append(esc(m.description())).append("</p>\n");
        b.append("<p><a href=\"").append(esc(m.canonical())).append("\">Open on Howlers</a></p>\n");
        b.append("</main>\n</body></html>\n");
        return b.toString();
    }

    private static void meta(StringBuilder b, String attr, String key, String value) {
        b.append("<meta ").append(attr).append("=\"").append(key).append("\" content=\"").append(esc(value)).append("\">\n");
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\t') out.append(c);
                }
            }
        }
        return out.toString();
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        String body = """
                User-agent: *
                Allow: /
                Disallow: /api/
                Disallow: /admin
                Disallow: /settings
                Disallow: /messages
                Disallow: /files
                Disallow: /notifications
                Disallow: /saved
                Disallow: /library
                Disallow: /funding
                Disallow: /onboarding
                Disallow: /s/
                Disallow: /collections/shared/
                Disallow: /mod-actions/
                Disallow: /*/submit
                Disallow: /*/mod

                Sitemap: %s/sitemap.xml
                """.formatted(seo.baseUrl());
        return ResponseEntity.ok().contentType(TEXT).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    /** Sitemap index: always an index, so adding pages later never changes the entry URL search engines know. */
    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemapIndex() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        String base = seo.baseUrl() + "/sitemaps/";
        entry(b, base + "static-1.xml");
        for (int i = 1; i <= seo.communityPages(); i++) entry(b, base + "communities-" + i + ".xml");
        for (int i = 1; i <= seo.postPages(); i++) entry(b, base + "posts-" + i + ".xml");
        entry(b, base + "podcasts-1.xml");
        b.append("</sitemapindex>\n");
        return xml(b.toString());
    }

    private static void entry(StringBuilder b, String loc) {
        b.append("  <sitemap><loc>").append(esc(loc)).append("</loc></sitemap>\n");
    }

    @GetMapping("/sitemaps/{name}.xml")
    public ResponseEntity<String> sitemap(@PathVariable String name) {
        Matcher m = SITEMAP.matcher(name);
        if (!m.matches()) throw ApiException.notFound("Sitemap");
        int page = Integer.parseInt(m.group(2));
        if (page < 1) throw ApiException.notFound("Sitemap");
        List<SitemapUrl> urls = switch (m.group(1)) {
            case "static" -> page == 1 ? seo.staticUrls() : null;
            case "communities" -> page <= seo.communityPages() ? seo.communities(page) : null;
            case "posts" -> page <= seo.postPages() ? seo.posts(page) : null;
            default -> page == 1 ? seo.podcasts() : null;
        };
        if (urls == null) throw ApiException.notFound("Sitemap");
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (SitemapUrl u : urls) {
            b.append("  <url><loc>").append(esc(u.loc())).append("</loc>");
            if (u.lastmod() != null) b.append("<lastmod>").append(DateTimeFormatter.ISO_INSTANT.format(u.lastmod())).append("</lastmod>");
            b.append("</url>\n");
        }
        b.append("</urlset>\n");
        return xml(b.toString());
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(XML).cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic()).body(body);
    }
}
