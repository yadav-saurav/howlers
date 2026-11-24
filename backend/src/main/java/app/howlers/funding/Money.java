package app.howlers.funding;

import java.util.List;
import java.util.Map;

/**
 * An amount of money in integer minor units (cents) of an ISO 4217 currency. Never floating point.
 *
 * <p>Every amount the API returns carries the {@code mode} it was recorded in ({@code demo}, {@code test} or {@code live}),
 * {@code demo = true} whenever no real money moved (demo and Stripe test mode), and for non-live money a label that
 * clients must show next to it. The label comes from the mode of the row, not from the server's current provider, so
 * old demo pools stay labelled as demo after a switch to Stripe.
 */
public record Money(long amountMinor, String currency, boolean demo, String mode, String label) {
    public static final String MODE_DEMO = "demo";
    public static final String MODE_TEST = "test";
    public static final String MODE_LIVE = "live";
    public static final String DEMO_LABEL = "DEMO, no real money";
    public static final String TEST_LABEL = "TEST MODE: no real money";

    /** Supported currencies and their minor-unit exponent. Kept small on purpose. */
    public static final Map<String, Integer> CURRENCIES = Map.of("USD", 2, "EUR", 2, "GBP", 2, "JPY", 0);
    public static final List<String> CURRENCY_ORDER = List.of("USD", "EUR", "GBP", "JPY");

    public static Money of(long amountMinor, String currency, String mode) {
        return new Money(amountMinor, currency, !MODE_LIVE.equals(mode), mode, labelFor(mode));
    }

    /** The label clients show next to an amount of this mode. Live money carries none. */
    public static String labelFor(String mode) {
        if (MODE_LIVE.equals(mode)) return null;
        if (MODE_TEST.equals(mode)) return TEST_LABEL;
        return DEMO_LABEL;
    }

    public static boolean supported(String currency) {
        return currency != null && CURRENCIES.containsKey(currency);
    }

    /** One major unit (e.g. 100 cents, or 1 yen). */
    public static long unit(String currency) {
        long u = 1;
        for (int i = 0; i < CURRENCIES.get(currency); i++) u *= 10;
        return u;
    }

    /** Smallest contribution: one major unit. */
    public static long minContribution(String currency) {
        return unit(currency);
    }

    /** Largest single contribution: 10,000 major units. */
    public static long maxContribution(String currency) {
        return Math.multiplyExact(10_000L, unit(currency));
    }

    /** Largest pool goal: 10,000,000 major units (keeps every sum far below 2^53, safe for JSON clients). */
    public static long maxGoal(String currency) {
        return Math.multiplyExact(10_000_000L, unit(currency));
    }
}
