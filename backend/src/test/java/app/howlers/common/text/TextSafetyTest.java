package app.howlers.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSafetyTest {
    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    @Test
    void stripsScriptsHandlersAndDangerousUrls() {
        String out = sanitizer.sanitize("<p onclick=\"x()\">Hi<script>alert(1)</script>"
                + "<img src=x onerror=alert(1)><a href=\"javascript:alert(1)\">a</a><a href=\"https://ok.example\">b</a></p>");
        assertThat(out).doesNotContain("script", "onclick", "onerror", "<img", "javascript:");
        assertThat(out).contains("href=\"https://ok.example\"", "rel=\"nofollow noopener noreferrer ugc\"");
    }

    @Test
    void producesReadablePlainText() {
        String text = sanitizer.toText(sanitizer.sanitize("<p>One</p><p>Two <strong>bold</strong></p>"));
        assertThat(text).isEqualTo("One\nTwo bold");
        assertThat(HtmlSanitizer.preview("a".repeat(400), 300)).hasSize(300).endsWith("…");
    }

    @Test
    void extractsMentionsCaseInsensitivelyAndBounded() {
        assertThat(Mentions.extract("hey @Mira_K and @mira_k, also email a@b.com and @x", 5)).containsExactly("mira_k");
        assertThat(Mentions.extract("@aaa @bbb @ccc", 2)).containsExactly("aaa", "bbb");
    }
}
