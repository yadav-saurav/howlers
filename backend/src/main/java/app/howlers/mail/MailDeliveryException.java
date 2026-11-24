package app.howlers.mail;

import java.time.Duration;

/**
 * A failed delivery attempt. {@code retryable} is true for 429, 5xx and network errors; false for anything retrying
 * can't fix (bad sender, rejected key, malformed request). {@code retryAfter} is the provider's hint, if it sent one.
 * Messages never contain the API key, the email body or its links.
 */
public class MailDeliveryException extends Exception {
    private final boolean retryable;
    private final int status;
    private final Duration retryAfter;

    public MailDeliveryException(String message, boolean retryable, int status, Duration retryAfter) {
        super(message);
        this.retryable = retryable;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public static MailDeliveryException retryable(String message, int status, Duration retryAfter) {
        return new MailDeliveryException(message, true, status, retryAfter);
    }

    public static MailDeliveryException permanent(String message, int status) {
        return new MailDeliveryException(message, false, status, null);
    }

    public boolean retryable() { return retryable; }
    /** HTTP status, or 0 when there was no HTTP response (network error, SMTP). */
    public int status() { return status; }
    public Duration retryAfter() { return retryAfter; }
}
