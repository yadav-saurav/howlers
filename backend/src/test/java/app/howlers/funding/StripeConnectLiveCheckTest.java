package app.howlers.funding;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.howlers.funding.payment.SdkStripeGateway;
import app.howlers.funding.payment.StripeGateway.AccountLink;
import app.howlers.funding.payment.StripeGateway.ConnectedAccount;
import app.howlers.funding.payment.StripeGateway.StripeGatewayException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Owner's live check of Stripe Connect against TEST mode (D-046). Not part of the suite: tagged {@code live} (excluded in
 * the pom) and only runs with {@code -Dstripe.live=true}. Reads STRIPE_SECRET_KEY from the file named by
 * {@code -Dstripe.envFile} (default backend/.env.local), never prints it, refuses anything but a test key, creates one
 * Express account and one onboarding link, prints only the account id prefix and whether the link was created, and
 * deletes the account again. It never creates a transfer. Run from backend/:
 *
 * <pre>./mvnw test -Dtest=StripeConnectLiveCheckTest -Dstripe.live=true -Dsurefire.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * If Connect isn't activated on the Stripe account, Stripe refuses the account creation; the test prints Stripe's error
 * code and message and fails, which means the owner must activate Connect in the dashboard first.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "stripe.live", matches = "true")
class StripeConnectLiveCheckTest {

    @Test
    void createsAndDeletesATestModeExpressAccount() throws Exception {
        Path env = Path.of(System.getProperty("stripe.envFile", ".env.local"));
        assumeTrue(Files.exists(env), "env file not found");
        String key = Files.readAllLines(env).stream().map(String::strip).filter(l -> l.startsWith("STRIPE_SECRET_KEY="))
                .map(l -> l.substring("STRIPE_SECRET_KEY=".length()).strip()).findFirst().orElse("");
        assumeTrue(!key.isEmpty(), "STRIPE_SECRET_KEY is not set");
        if (!key.startsWith("sk_test_") && !key.startsWith("rk_test_")) throw new IllegalStateException("Refusing: STRIPE_SECRET_KEY is not a test key");

        SdkStripeGateway gw = new SdkStripeGateway(key);
        ConnectedAccount account;
        try {
            account = gw.createExpressAccount("US", Map.of("howlers_check", "true"), "howlers-connect-live-check-" + System.currentTimeMillis());
        } catch (StripeGatewayException e) {
            System.out.println("Stripe refused the account: code=" + e.code() + " message=" + e.getMessage());
            throw e;
        }
        try {
            System.out.println("Connected account created: " + account.id().substring(0, Math.min(10, account.id().length())) + "...");
            System.out.println("Account status: transfers=" + account.transfersCapability() + ", detailsSubmitted=" + account.detailsSubmitted()
                    + ", requirementsDue=" + account.requirementsDue());
            AccountLink link = gw.createAccountLink(account.id(), "http://localhost:5173/funding/payouts?connect=refresh",
                    "http://localhost:5173/funding/payouts?connect=return");
            System.out.println("Onboarding link created: " + (link != null && link.url() != null && link.url().startsWith("https://")));
        } finally {
            gw.deleteAccount(account.id());
            System.out.println("Test account deleted");
        }
    }
}
