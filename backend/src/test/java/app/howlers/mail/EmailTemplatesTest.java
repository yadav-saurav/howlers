package app.howlers.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailTemplatesTest {
    private final EmailTemplates templates = new EmailTemplates(EmailTemplates.BRAND, "https://howlers.example.com/");

    @Test
    void verificationHasLinkExpiryAndNotYouLineInBothParts() {
        OutgoingEmail m = templates.verification("ann@example.com", "ann_k", "tok-123_ABC", Duration.ofHours(24));
        String link = "https://howlers.example.com/verify-email?token=tok-123_ABC";
        assertThat(m.tag()).isEqualTo("verify-email");
        assertThat(m.subject()).isEqualTo("Verify your Howlers email");
        assertThat(m.toName()).isEqualTo("ann_k");
        assertThat(m.text()).contains(link, "24 hours", "Didn't create a Howlers account?", "Hi ann_k",
                "Find your pack & howl together");
        assertThat(m.html()).contains("href=\"" + link + "\"", "24 hours", "Didn't create a Howlers account?",
                "Find your pack &amp; howl together", "<html lang=\"en\">", "role=\"presentation\"");
    }

    @Test
    void resetHasLinkExpiryAndNotYouLine() {
        OutgoingEmail m = templates.passwordReset("ann@example.com", "ann_k", "abc", Duration.ofHours(1));
        assertThat(m.tag()).isEqualTo("reset-password");
        assertThat(m.text()).contains("https://howlers.example.com/reset-password?token=abc", "1 hour", "Didn't request this?");
        assertThat(m.html()).contains("https://howlers.example.com/reset-password?token=abc", "1 hour", "Didn't request this?");
    }

    @Test
    void userValuesAreEscapedAndTokensUrlEncoded() {
        EmailTemplates t = new EmailTemplates("Str<a>ta & co", "https://x.test");
        OutgoingEmail m = t.verification("a@b.c", "<img src=x onerror=alert(1)>", "a+b/c=\"d", Duration.ofMinutes(30));
        assertThat(m.html()).doesNotContain("<img", "onerror=alert(1)>", "Str<a>ta")
                .contains("&lt;img src=x onerror=alert(1)&gt;", "Str&lt;a&gt;ta &amp; co", "30 minutes");
        assertThat(m.html()).contains("token=a%2Bb%2Fc%3D%22d");
        assertThat(m.text()).contains("token=a%2Bb%2Fc%3D%22d");
    }

    @Test
    void noTrackingPixelsOrRemoteResources() {
        OutgoingEmail m = templates.verification("ann@example.com", "ann", "t", Duration.ofHours(24));
        assertThat(m.html().toLowerCase()).doesNotContain("<img", "<link", "<script", "url(", "@import");
    }

    @Test
    void humanizesDurations() {
        assertThat(EmailTemplates.humanize(Duration.ofHours(24))).isEqualTo("24 hours");
        assertThat(EmailTemplates.humanize(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(EmailTemplates.humanize(Duration.ofMinutes(90))).isEqualTo("90 minutes");
        assertThat(EmailTemplates.humanize(Duration.ofMinutes(1))).isEqualTo("1 minute");
    }

    @Test
    void masksAddresses() {
        assertThat(OutgoingEmail.mask("ann@example.com")).isEqualTo("a***@example.com");
        assertThat(OutgoingEmail.mask("nope")).isEqualTo("***");
    }
}
