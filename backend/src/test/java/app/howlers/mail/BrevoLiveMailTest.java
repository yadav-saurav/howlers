package app.howlers.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Reader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Live check against the real Brevo API (D-038). Skipped in the normal suite; it runs only with
 * {@code ./mvnw.cmd test -Dtest=BrevoLiveMailTest -Dhowlers.liveMail=true} and only when {@code backend/.env.local}
 * (or the file named by {@code -Dhowlers.envFile=...}) has BREVO_API_KEY, MAIL_FROM_ADDRESS and MAIL_TEST_TO.
 * Sends one verification-style email with a dummy token to MAIL_TEST_TO. The key is never printed.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "howlers.liveMail", matches = "true")
class BrevoLiveMailTest {

    @Test
    void sendsOneVerificationStyleEmail() throws IOException, MailDeliveryException {
        Path file = Path.of(System.getProperty("howlers.envFile", ".env.local"));
        assumeTrue(Files.isRegularFile(file), "no " + file);
        Properties env = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            env.load(r);
        }
        String key = env.getProperty("BREVO_API_KEY", "").trim();
        String from = env.getProperty("MAIL_FROM_ADDRESS", "").trim();
        String to = env.getProperty("MAIL_TEST_TO", "").trim();
        assumeTrue(!key.isEmpty() && !from.isEmpty() && !to.isEmpty(), "BREVO_API_KEY, MAIL_FROM_ADDRESS and MAIL_TEST_TO are required");

        String publicUrl = env.getProperty("APP_PUBLIC_URL", "http://localhost:5173").trim();
        BrevoMailTransport brevo = new BrevoMailTransport(HttpClient.newHttpClient(),
                env.getProperty("BREVO_BASE_URL", "https://api.brevo.com"), key, from,
                env.getProperty("MAIL_FROM_NAME", EmailTemplates.BRAND), env.getProperty("MAIL_REPLY_TO", ""), false, Duration.ofSeconds(15));
        OutgoingEmail m = new EmailTemplates(EmailTemplates.BRAND, publicUrl)
                .verification(to, "live_check", "live-check-not-a-real-token", Duration.ofHours(24));
        String id = brevo.send(m);
        System.out.println("Brevo accepted the live check for " + OutgoingEmail.mask(to) + (id == null ? "" : ", messageId " + id));
        assertThat(id).isNotBlank();
    }
}
