package app.howlers.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.howlers.funding.payment.PaymentProvider.CheckoutRequest;
import app.howlers.funding.payment.SdkStripeGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Owner's live check against Stripe TEST mode. Not part of the suite: it is tagged {@code stripe-live} (excluded in the
 * pom) and only runs with {@code -Dstripe.live=true}. It reads STRIPE_SECRET_KEY from backend/.env.local (never printed),
 * refuses anything but an {@code sk_test_} key, creates a 5.00 USD Checkout Session and prints only the session id
 * prefix and the hosted URL. Run from backend/:
 *
 * <pre>./mvnw test -Dtest=StripeLiveCheckTest -Dstripe.live=true -Dsurefire.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@Tag("stripe-live")
@EnabledIfSystemProperty(named = "stripe.live", matches = "true")
class StripeLiveCheckTest {

    @Test
    void createsATestModeCheckoutSession() throws Exception {
        Path env = Path.of(".env.local");
        assumeTrue(Files.exists(env), "backend/.env.local not found");
        String key = Files.readAllLines(env).stream().map(String::strip).filter(l -> l.startsWith("STRIPE_SECRET_KEY="))
                .map(l -> l.substring("STRIPE_SECRET_KEY=".length()).strip()).findFirst().orElse("");
        assumeTrue(!key.isEmpty(), "STRIPE_SECRET_KEY is not set in backend/.env.local");
        if (!key.startsWith("sk_test_")) throw new IllegalStateException("Refusing: STRIPE_SECRET_KEY is not a test key (sk_test_...)");

        var session = new SdkStripeGateway(key).createCheckoutSession(new CheckoutRequest(false, 500, "USD",
                "Howlers moderator pool: live test-mode check", "Test-mode check of the Stripe integration. No real money.",
                "http://localhost:5173/funding?checkout=success&session_id={CHECKOUT_SESSION_ID}", "http://localhost:5173/funding?checkout=cancelled",
                "live-check", Map.of("howlers_check", "true"), Instant.now().plusSeconds(3600).getEpochSecond(),
                "howlers-live-check-" + System.currentTimeMillis()));
        assertThat(session.id()).startsWith("cs_test_");
        System.out.println("Stripe test session: " + session.id().substring(0, 12) + "...");
        System.out.println("Checkout URL: " + session.url());
    }
}
