package app.howlers.mail;

import java.util.Objects;
import java.util.UUID;

/**
 * One transactional email, fully rendered. {@code tag} names the kind of message (e.g. {@code verify-email}) and is the
 * only description of it that non-log transports write to the log: bodies carry one-time links and are never logged
 * outside {@code MAIL_MODE=log}. {@code idempotencyKey} stays the same across retries so the provider can drop
 * duplicates if an earlier attempt was accepted but its response was lost.
 */
public record OutgoingEmail(String to, String toName, String subject, String html, String text, String tag,
                            String idempotencyKey) {
    public OutgoingEmail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tag, "tag");
        if (idempotencyKey == null) idempotencyKey = UUID.randomUUID().toString();
    }

    public OutgoingEmail(String to, String toName, String subject, String html, String text, String tag) {
        this(to, toName, subject, html, text, tag, null);
    }

    /** {@code a***@example.com}: enough to correlate a log line with a support request, not enough to harvest. */
    public static String mask(String email) {
        if (email == null) return "?";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
