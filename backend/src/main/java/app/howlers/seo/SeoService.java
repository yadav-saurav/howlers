package app.howlers.seo;

import app.howlers.common.config.AppProperties;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Metadata for link previews and search engines (D-039). Crawlers don't run JavaScript, so nginx sends their page
 * requests here and gets a small HTML document with Open Graph / Twitter tags instead of the SPA shell.
 *
 * <p>Only content anyone signed-out could read is described: posts that aren't removed, held or deleted, in
 * communities that aren't private and whose status is ACTIVE; public profiles of active accounts; published podcast
 * episodes. Everything else (including content that doesn't exist) gets the same generic Howlers tags with
 * {@code noindex}, so a preview never reveals whether something hidden exists.
 */
@Service
public class SeoService {
    public static final String TAGLINE = "Find your pack & howl together";
    public static final String DEFAULT_DESCRIPTION = "Howlers is home for communities that like each other: threads that "
            + "make sense, an AI that cites its sources, live messages, polls and community podcasts.";

    private static final Pattern POST = Pattern.compile("^/(?:c/[A-Za-z0-9_]{1,24}/)?p/(\\d{1,18})(?:/[^/]*)?/?$");
    private static final Pattern COMMUNITY = Pattern.compile("^/c/([A-Za-z0-9_]{1,24})/?$");
    private static final Pattern USER = Pattern.compile("^/u/([A-Za-z0-9_]{1,21})/?$");
    private static final Pattern EPISODE = Pattern.compile("^/podcasts/(\\d{1,18})/?$");

    /** Public SPA pages with fixed titles. */
    private static final Map<String, String[]> STATIC = Map.of(
            "/communities", new String[]{"Communities", "Browse and join communities on Howlers."},
            "/explore", new String[]{"Explore", "Discover communities, topics and conversations on Howlers."},
            "/podcasts", new String[]{"Podcasts", "Community podcasts on Howlers: episodes made by the people in each community."},
            "/leaderboard", new String[]{"Leaderboard", "The people keeping Howlers communities helpful."},
            "/about", new String[]{"About Howlers", DEFAULT_DESCRIPTION},
            "/terms", new String[]{"Terms of Service", "The terms for using Howlers."},
            "/privacy", new String[]{"Privacy Policy", "How Howlers handles your data."},
            "/home", new String[]{"Home", DEFAULT_DESCRIPTION});

    /** What the preview document says. {@code canonical} is absolute. */
    public record PageMeta(String title, String description, String canonical, String type, String image,
                           String imageAlt, boolean noindex, String context, Instant published) {}

    private final JdbcClient jdbc;
    private final AppProperties app;
    private final SeoProperties seo;

    public SeoService(JdbcClient jdbc, AppProperties app, SeoProperties seo) {
        this.jdbc = jdbc;
        this.app = app;
        this.seo = seo;
    }

    public String baseUrl() {
        String u = app.app().publicUrl();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public String imageUrl() {
        return baseUrl() + "/og/howlers.png";
    }

    /** {@code rawPath} is the request path as the browser sent it (query and fragment are ignored). */
    public PageMeta resolve(String rawPath) {
        String path = normalize(rawPath);
        if (path == null) return generic(false);
        if (path.equals("/")) return landing();
        Matcher m;
        if ((m = POST.matcher(path)).matches()) return post(Long.parseLong(m.group(1))).orElseGet(() -> generic(true));
        if ((m = COMMUNITY.matcher(path)).matches()) return community(m.group(1)).orElseGet(() -> generic(true));
        if ((m = USER.matcher(path)).matches()) return user(m.group(1)).orElseGet(() -> generic(true));
        if ((m = EPISODE.matcher(path)).matches()) return episode(Long.parseLong(m.group(1))).orElseGet(() -> generic(true));
        String[] s = STATIC.get(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        if (s != null) return page(s[0] + " · Howlers", s[1], baseUrl() + path, "website", false, null, null);
        return generic(true);
    }

    static String normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2000) return null;
        try {
            String p = URI.create(raw.trim()).getPath();
            if (p == null || p.isEmpty() || !p.startsWith("/") || p.contains("..")) return null;
            return p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PageMeta landing() {
        return page("Howlers · " + TAGLINE, DEFAULT_DESCRIPTION, baseUrl() + "/", "website", false, null, null);
    }

    /** The fallback: generic tags, canonical = the landing page. */
    private PageMeta generic(boolean noindex) {
        return page("Howlers · " + TAGLINE, DEFAULT_DESCRIPTION, baseUrl() + "/", "website", noindex, null, null);
    }

    private PageMeta page(String title, String description, String canonical, String type, boolean noindex,
                          String context, Instant published) {
        return new PageMeta(title, description, canonical, type, imageUrl(), "Howlers: " + TAGLINE, noindex, context, published);
    }

    Optional<PageMeta> post(long id) {
        return jdbc.sql("""
                        SELECT p.id, p.title, p.body_text, p.type, p.url, p.created_at, c.slug, c.name,
                               CASE WHEN u.status = 'ACTIVE' AND u.deleted_at IS NULL THEN u.username END AS author
                        FROM posts p
                        JOIN communities c ON c.id = p.community_id
                        LEFT JOIN users u ON u.id = p.author_id
                        WHERE p.id = ? AND p.removed = FALSE AND p.pending = FALSE AND p.deleted_at IS NULL
                          AND c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'""")
                .param(id)
                .query((rs, i) -> {
                    String slug = rs.getString("slug");
                    String author = rs.getString("author");
                    String excerpt = excerpt(rs.getString("body_text"), 200);
                    if (excerpt.isEmpty() && rs.getString("url") != null) excerpt = "Link: " + host(rs.getString("url"));
                    if (excerpt.isEmpty()) excerpt = "A discussion in c/" + slug + " on Howlers.";
                    String context = "c/" + slug + (author == null ? "" : " · posted by u/" + author);
                    return page(clip(rs.getString("title"), 110) + " · c/" + slug, excerpt,
                            baseUrl() + "/c/" + slug + "/p/" + rs.getLong("id"), "article", false, context,
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    Optional<PageMeta> community(String slug) {
        return jdbc.sql("""
                        SELECT slug, name, description, member_count FROM communities
                        WHERE lower(slug) = lower(?) AND visibility <> 'PRIVATE' AND status = 'ACTIVE'""")
                .param(slug)
                .query((rs, i) -> {
                    String s = rs.getString("slug");
                    String d = excerpt(rs.getString("description"), 200);
                    int members = rs.getInt("member_count");
                    return page(clip(rs.getString("name"), 80) + " (c/" + s + ") · Howlers",
                            d.isEmpty() ? "Join c/" + s + " on Howlers." : d, baseUrl() + "/c/" + s, "website", false,
                            members + (members == 1 ? " member" : " members"), null);
                })
                .optional();
    }

    Optional<PageMeta> user(String username) {
        return jdbc.sql("""
                        SELECT username, display_name, bio FROM users
                        WHERE lower(username) = lower(?) AND status = 'ACTIVE' AND deleted_at IS NULL AND profile_public""")
                .param(username)
                .query((rs, i) -> {
                    String u = rs.getString("username");
                    String name = rs.getString("display_name");
                    String bio = excerpt(rs.getString("bio"), 200);
                    return page((name == null || name.isBlank() || name.equalsIgnoreCase(u) ? "u/" + u : clip(name, 50) + " (u/" + u + ")") + " · Howlers",
                            bio.isEmpty() ? "u/" + u + " on Howlers." : bio, baseUrl() + "/u/" + u, "profile", false, null, null);
                })
                .optional();
    }

    Optional<PageMeta> episode(long id) {
        return jdbc.sql("""
                        SELECT e.id, e.title, e.summary, e.published_at, c.slug FROM podcast_episodes e
                        JOIN communities c ON c.id = e.community_id
                        WHERE e.id = ? AND e.status = 'PUBLISHED' AND c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'""")
                .param(id)
                .query((rs, i) -> {
                    String s = excerpt(rs.getString("summary"), 200);
                    OffsetDateTime pub = rs.getObject("published_at", OffsetDateTime.class);
                    return page(clip(rs.getString("title"), 110) + " · c/" + rs.getString("slug") + " podcast",
                            s.isEmpty() ? "A community podcast episode on Howlers." : s,
                            baseUrl() + "/podcasts/" + rs.getLong("id"), "article", false,
                            "Podcast from c/" + rs.getString("slug"), pub == null ? null : pub.toInstant());
                })
                .optional();
    }

    // ------------------------------------------------------------------ sitemaps

    public record SitemapUrl(String loc, Instant lastmod) {}

    public int postPages() {
        long n = jdbc.sql("""
                        SELECT count(*) FROM (SELECT 1 FROM posts p JOIN communities c ON c.id = p.community_id
                        WHERE p.removed = FALSE AND p.pending = FALSE AND p.deleted_at IS NULL
                          AND c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE' LIMIT ?) x""")
                .param(props().maxPosts()).query(Long.class).single();
        return (int) Math.max(1, (n + props().pageSize() - 1) / props().pageSize());
    }

    public int communityPages() {
        long n = jdbc.sql("SELECT count(*) FROM communities WHERE visibility <> 'PRIVATE' AND status = 'ACTIVE'")
                .query(Long.class).single();
        return (int) Math.max(1, (n + props().pageSize() - 1) / props().pageSize());
    }

    public List<SitemapUrl> staticUrls() {
        return List.of("/", "/communities", "/explore", "/podcasts", "/leaderboard", "/about", "/terms", "/privacy").stream()
                .map(p -> new SitemapUrl(baseUrl() + p, null)).toList();
    }

    public List<SitemapUrl> communities(int page) {
        return jdbc.sql("""
                        SELECT c.slug, GREATEST(c.updated_at, (SELECT max(p.created_at) FROM posts p WHERE p.community_id = c.id
                               AND p.removed = FALSE AND p.deleted_at IS NULL)) AS lastmod
                        FROM communities c WHERE c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'
                        ORDER BY c.id LIMIT ? OFFSET ?""")
                .params(props().pageSize(), (long) (page - 1) * props().pageSize())
                .query((rs, i) -> new SitemapUrl(baseUrl() + "/c/" + rs.getString(1), instant(rs.getObject(2, OffsetDateTime.class))))
                .list();
    }

    /** The most recent public posts, newest first, up to {@code sitemap-max-posts}. */
    public List<SitemapUrl> posts(int page) {
        long offset = (long) (page - 1) * props().pageSize();
        if (offset >= props().maxPosts()) return List.of();
        int limit = (int) Math.min(props().pageSize(), props().maxPosts() - offset);
        return jdbc.sql("""
                        SELECT p.id, c.slug, COALESCE(p.edited_at, p.created_at) AS lastmod
                        FROM posts p JOIN communities c ON c.id = p.community_id
                        WHERE p.removed = FALSE AND p.pending = FALSE AND p.deleted_at IS NULL
                          AND c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'
                        ORDER BY p.id DESC LIMIT ? OFFSET ?""")
                .params(limit, offset)
                .query((rs, i) -> new SitemapUrl(baseUrl() + "/c/" + rs.getString(2) + "/p/" + rs.getLong(1),
                        instant(rs.getObject(3, OffsetDateTime.class))))
                .list();
    }

    public List<SitemapUrl> podcasts() {
        return jdbc.sql("""
                        SELECT e.id, COALESCE(e.published_at, e.updated_at) FROM podcast_episodes e
                        JOIN communities c ON c.id = e.community_id
                        WHERE e.status = 'PUBLISHED' AND c.visibility <> 'PRIVATE' AND c.status = 'ACTIVE'
                        ORDER BY e.id DESC LIMIT 50000""")
                .query((rs, i) -> new SitemapUrl(baseUrl() + "/podcasts/" + rs.getLong(1), instant(rs.getObject(2, OffsetDateTime.class))))
                .list();
    }

    private SeoProperties props() {
        return seo;
    }

    // ------------------------------------------------------------------ text helpers

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    static String excerpt(String text, int max) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return clip(t, max);
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        return (cut > max / 2 ? s.substring(0, cut) : s.substring(0, max - 1)).trim() + "…";
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "external link" : h.startsWith("www.") ? h.substring(4) : h;
        } catch (IllegalArgumentException e) {
            return "external link";
        }
    }
}
