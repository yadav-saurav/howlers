package app.howlers.mail;

import java.time.Duration;

/**
 * Exponential backoff for retryable delivery failures: attempt 1 is immediate, attempt n waits
 * {@code initialDelay * multiplier^(n-2)}, capped at {@code maxDelay}. A provider's Retry-After hint wins when it is
 * longer, but is capped as well so one bad header can't park the mail thread for an hour.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {
    /** 4 attempts over about 14 seconds (2 s, 4 s, 8 s): enough for a throttle window or a short provider blip. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(4, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(30));

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
    }

    /** Delay before attempt number {@code attempt} (2-based; attempt 1 never waits). */
    public Duration delayBefore(int attempt, Duration retryAfterHint) {
        if (attempt <= 1) return Duration.ZERO;
        double factor = Math.pow(multiplier, attempt - 2);
        long millis = (long) Math.min(initialDelay.toMillis() * factor, maxDelay.toMillis());
        if (retryAfterHint != null && retryAfterHint.toMillis() > millis) {
            millis = Math.min(retryAfterHint.toMillis(), maxDelay.toMillis());
        }
        return Duration.ofMillis(millis);
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }
}
