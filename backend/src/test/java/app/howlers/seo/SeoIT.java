package app.howlers.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Link previews, robots.txt and sitemaps only ever describe content a signed-out visitor could read (D-039). */
class SeoIT extends IntegrationTest {
    @Autowired SeoController seo;
    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;

    private long user(String prefix) {
        String name = prefix + (System.nanoTime() % 1_000_000);
        return auth.register(new RegisterRequest(name, name + "@seo.local", "password1", null), "t", "127.0.0.1").response().user().id();
    }

    private String username(long id) {
        return jdbc.sql("SELECT username FROM users WHERE id = ?").param(id).query(String.class).single();
    }

    private String community(String visibility, String status) {
        String slug = "s" + (System.nanoTime() % 1_000_000_000_000L);
        jdbc.sql("INSERT INTO communities (slug, name, description, visibility, status) VALUES (?, ?, ?, ?, ?)")
                .params(slug, "Night <Owls> & \"Friends\"", "Talk about the moon after dark.", visibility, status).update();
        return slug;
    }

    private long post(String slug, long author, String title, String body) {
        return jdbc.sql("""
                        INSERT INTO posts (community_id, author_id, type, title, body_html, body_text)
                        VALUES ((SELECT id FROM communities WHERE slug = ?), ?, 'TEXT', ?, '', ?) RETURNING id""")
                .params(slug, author, title, body).query(Long.class).single();
    }

    private String page(String path) {
        return seo.page(null, path).getBody();
    }

    private static void assertGeneric(String html) {
        assertThat(html).contains("<title>Howlers · Find your pack &amp; howl together</title>")
                .contains("<meta name=\"robots\" content=\"noindex\">")
                .contains("<link rel=\"canonical\" href=\"http://localhost:5173/\">");
    }

    @Test
    void publicPostPreviewHasEscapedTitleExcerptCanonicalAndImage() {
        long ana = user("sa");
        String slug = community("PUBLIC", "ACTIVE");
        long id = post(slug, ana, "Moon <script>alert(1)</script> \"phases\"", "The waxing gibbous   is\nthe best phase. ".repeat(20));

        String html = page("/c/" + slug + "/p/" + id + "/moon-phases?utm_source=x#c-3");
        assertThat(html).contains("<title>Moon &lt;script&gt;alert(1)&lt;/script&gt; &quot;phases&quot; · c/" + slug + "</title>")
                .doesNotContain("<script>")
                .contains("<meta property=\"og:type\" content=\"article\">")
                .contains("<link rel=\"canonical\" href=\"http://localhost:5173/c/" + slug + "/p/" + id + "\">")
                .contains("<meta property=\"og:url\" content=\"http://localhost:5173/c/" + slug + "/p/" + id + "\">")
                .contains("<meta property=\"og:image\" content=\"http://localhost:5173/og/howlers.png\">")
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
                .contains("The waxing gibbous is the best phase.")
                .contains("posted by u/" + username(ana))
                .doesNotContain("noindex");
        // The short /p/:id link resolves to the same canonical URL.
        assertThat(page("/p/" + id)).contains("href=\"http://localhost:5173/c/" + slug + "/p/" + id + "\"");
        // The header nginx sets wins over the query parameter.
        assertThat(seo.page("/", "/p/" + id).getBody()).contains("og:type\" content=\"article");

        String c = page("/c/" + slug);
        assertThat(c).contains("Night &lt;Owls&gt; &amp; &quot;Friends&quot; (c/" + slug + ")")
                .contains("Talk about the moon after dark.").contains("0 members");
    }

    @Test
    void hiddenContentFallsBackToGenericTags() {
        long ana = user("sb");
        String priv = community("PRIVATE", "ACTIVE");
        String banned = community("PUBLIC", "BANNED");
        String quarantined = community("PUBLIC", "QUARANTINED");
        String pub = community("PUBLIC", "ACTIVE");
        long secret = post(priv, ana, "Private plans", "secret body");
        long removed = post(pub, ana, "Removed title", "removed body");
        long held = post(pub, ana, "Held title", "held body");
        long deleted = post(pub, ana, "Deleted title", "deleted body");
        long inBanned = post(banned, ana, "Banned title", "banned body");
        jdbc.sql("UPDATE posts SET removed = TRUE WHERE id = ?").param(removed).update();
        jdbc.sql("UPDATE posts SET removed = TRUE, pending = TRUE WHERE id = ?").param(held).update();
        jdbc.sql("UPDATE posts SET deleted_at = now() WHERE id = ?").param(deleted).update();

        for (String path : new String[]{"/c/" + priv + "/p/" + secret, "/p/" + secret, "/p/" + removed, "/p/" + held,
                "/p/" + deleted, "/p/" + inBanned, "/p/999999999", "/c/" + priv, "/c/" + banned, "/c/" + quarantined,
                "/c/does_not_exist", "/settings", "/messages/3", "/admin"}) {
            String html = page(path);
            assertGeneric(html);
            assertThat(html).as(path).doesNotContain("Private plans").doesNotContain("secret body").doesNotContain("Removed title")
                    .doesNotContain("Held title").doesNotContain("Deleted title").doesNotContain("Banned title")
                    .doesNotContain("Owls");
        }

        long bob = user("sc");
        assertThat(page("/u/" + username(bob))).contains("u/" + username(bob) + " · Howlers").doesNotContain("noindex");
        jdbc.sql("UPDATE users SET bio = 'Private bio', profile_public = FALSE WHERE id = ?").param(bob).update();
        assertGeneric(page("/u/" + username(bob)));
        long sus = user("sd");
        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE id = ?").param(sus).update();
        assertGeneric(page("/u/" + username(sus)));

        // Unparseable input is treated like an unknown page.
        assertThat(page("http://[bad")).contains("<title>Howlers");
        assertThat(page("/")).contains("<link rel=\"canonical\" href=\"http://localhost:5173/\">").doesNotContain("noindex");
        assertThat(page("/communities")).contains("<title>Communities · Howlers</title>");
    }

    @Test
    void podcastEpisodesArePreviewedOnlyWhenPublished() {
        String pub = community("PUBLIC", "ACTIVE");
        long ep = jdbc.sql("""
                        INSERT INTO podcast_episodes (community_id, title, summary, type, status, published_at)
                        VALUES ((SELECT id FROM communities WHERE slug = ?), 'Moonrise weekly', 'This week: harvest moon.', 'DISCUSSION', 'PUBLISHED', now())
                        RETURNING id""").param(pub).query(Long.class).single();
        assertThat(page("/podcasts/" + ep)).contains("Moonrise weekly").contains("This week: harvest moon.")
                .contains("href=\"http://localhost:5173/podcasts/" + ep + "\"");
        jdbc.sql("UPDATE podcast_episodes SET status = 'DRAFT' WHERE id = ?").param(ep).update();
        assertGeneric(page("/podcasts/" + ep));
    }

    @Test
    void robotsAndSitemapsListOnlyPublicContent() {
        long ana = user("se");
        String pub = community("PUBLIC", "ACTIVE");
        String priv = community("PRIVATE", "ACTIVE");
        long visible = post(pub, ana, "Visible", "x");
        long hidden = post(priv, ana, "Hidden", "x");
        long removed = post(pub, ana, "Removed", "x");
        jdbc.sql("UPDATE posts SET removed = TRUE WHERE id = ?").param(removed).update();

        String robots = seo.robots().getBody();
        assertThat(robots).contains("Disallow: /api/").contains("Disallow: /admin").contains("Disallow: /settings")
                .contains("Disallow: /messages").contains("Disallow: /files").contains("Sitemap: http://localhost:5173/sitemap.xml");

        String index = seo.sitemapIndex().getBody();
        assertThat(index).contains("<sitemapindex").contains("http://localhost:5173/sitemaps/static-1.xml")
                .contains("http://localhost:5173/sitemaps/communities-1.xml").contains("http://localhost:5173/sitemaps/posts-1.xml")
                .contains("http://localhost:5173/sitemaps/podcasts-1.xml");

        String posts = seo.sitemap("posts-1").getBody();
        assertThat(posts).contains("<loc>http://localhost:5173/c/" + pub + "/p/" + visible + "</loc>")
                .doesNotContain("/p/" + hidden + "<").doesNotContain("/p/" + removed + "<").contains("<lastmod>");
        String communities = seo.sitemap("communities-1").getBody();
        assertThat(communities).contains("/c/" + pub + "</loc>").doesNotContain("/c/" + priv + "<");
        assertThat(seo.sitemap("static-1").getBody()).contains("<loc>http://localhost:5173/</loc>");
        assertThatThrownBy(() -> seo.sitemap("posts-999")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> seo.sitemap("users-1")).isInstanceOf(ApiException.class);
    }
}
