package app.howlers.seo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sitemap sizing ({@code howlers.seo.*}, D-039). One sitemap file holds at most 50,000 URLs by the protocol. */
@ConfigurationProperties(prefix = "howlers.seo")
public record SeoProperties(Integer sitemapPageSize, Integer sitemapMaxPosts) {
    public int pageSize() {
        return sitemapPageSize == null || sitemapPageSize <= 0 ? 10_000 : Math.min(sitemapPageSize, 50_000);
    }

    public int maxPosts() {
        return sitemapMaxPosts == null || sitemapMaxPosts < 0 ? 100_000 : sitemapMaxPosts;
    }
}
