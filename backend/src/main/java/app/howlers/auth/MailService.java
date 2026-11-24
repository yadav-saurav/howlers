package app.howlers.auth;

import app.howlers.common.config.AppProperties;
import app.howlers.mail.EmailTemplates;
import app.howlers.mail.MailDispatcher;
import app.howlers.auth.device.UserAgentFamily;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * The auth emails. Rendering happens here; delivery goes through {@link MailDispatcher}, which sends after the
 * surrounding transaction commits, off the request thread, with retries (D-038). The transport ({@code log},
 * {@code smtp} or {@code brevo}) comes from {@code MAIL_MODE}.
 */
@Service
public class MailService {
    private final MailDispatcher dispatcher;
    private final EmailTemplates templates;

    public MailService(AppProperties props, MailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.templates = new EmailTemplates(EmailTemplates.BRAND, props.app().publicUrl());
    }

    public void sendVerification(String to, String username, String token, Duration expiresIn) {
        dispatcher.submit(templates.verification(to, username, token, expiresIn));
    }

    public void sendPasswordReset(String to, String username, String token, Duration expiresIn) {
        dispatcher.submit(templates.passwordReset(to, username, token, expiresIn));
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    /** method: PASSWORD, PASSWORD_TOTP, PASSWORD_RECOVERY or PASSKEY. */
    public void sendNewSignIn(String to, String username, Instant at, UserAgentFamily device, String method) {
        dispatcher.submit(templates.newSignIn(to, username, WHEN.format(at), device.describe(), describeMethod(method)));
    }

    static String describeMethod(String method) {
        return switch (method == null ? "" : method) {
            case "PASSKEY" -> "a passkey";
            case "PASSWORD_TOTP" -> "password and authenticator code";
            case "PASSWORD_RECOVERY" -> "password and a recovery code";
            default -> "password";
        };
    }
}
