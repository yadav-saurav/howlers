package app.howlers.mail;

/** Delivers one rendered email. Implementations: {@link LogMailTransport}, {@link SmtpMailTransport}, {@link BrevoMailTransport}. */
public interface MailTransport {
    /** Short name for logs: {@code log}, {@code smtp}, {@code brevo}. */
    String name();

    /**
     * Sends the message once (no retries here; {@link MailDispatcher} owns the retry policy).
     *
     * @return the provider's message id, or null when it has none
     * @throws MailDeliveryException with {@code retryable} set for throttling, server errors and network failures
     */
    String send(OutgoingEmail email) throws MailDeliveryException;
}
