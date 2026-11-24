package app.howlers.spam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpamSignalsTest {

    @Test
    void contentHashIgnoresCasePunctuationAndSpacing() {
        assertThat(SpamSignals.contentHash("Buy NOW!!", "Cheap   watches, today."))
                .isEqualTo(SpamSignals.contentHash("buy now", "cheap watches today"));
        assertThat(SpamSignals.contentHash("Buy now", "cheap watches"))
                .isNotEqualTo(SpamSignals.contentHash("Buy now", "cheap shoes"));
    }

    @Test
    void urlsNormaliseSchemeWwwFragmentAndTrailingSlash() {
        assertThat(SpamSignals.linkHash("https://www.Example.com/deal/?id=1#top"))
                .isEqualTo(SpamSignals.linkHash("http://example.com/deal?id=1"));
        assertThat(SpamSignals.linkHash("https://example.com/deal?id=2"))
                .isNotEqualTo(SpamSignals.linkHash("https://example.com/deal?id=1"));
        assertThat(SpamSignals.linkHash(null)).isNull();
    }

    @Test
    void countsLinksAndMentions() {
        assertThat(SpamSignals.linksInText("see https://a.io and http://b.io/x, not ftp://c.io")).isEqualTo(2);
        assertThat(SpamSignals.linksInPost("<p><a href=\"https://a.io\">a</a> <a href='http://b.io'>b</a></p>", "https://c.io"))
                .isEqualTo(3);
        assertThat(SpamSignals.linksInPost("", null)).isZero();
        assertThat(SpamSignals.mentionCount("@ann @bob @ann email@me.com")).isEqualTo(2);
    }
}
