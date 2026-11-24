package app.howlers.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unique suffixes for test usernames, slugs and emails. Integration tests share one database per run, so suffixes
 * taken from the clock (System.nanoTime() modulo N) can repeat and fail a test with "That name is taken". A counter
 * never repeats within a run; the random start keeps values varied across runs. Always 7 digits.
 */
public final class TestIds {
    private static final AtomicLong NEXT = new AtomicLong(1_000_000 + ThreadLocalRandom.current().nextLong(4_000_000));

    private TestIds() {}

    public static String suffix() {
        return Long.toString(NEXT.getAndIncrement());
    }
}
