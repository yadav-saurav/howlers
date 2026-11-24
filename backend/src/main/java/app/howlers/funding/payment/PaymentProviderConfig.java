package app.howlers.funding.payment;

import app.howlers.funding.FundingProperties;
import app.howlers.funding.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the payment provider at startup. There is deliberately no fallback: an unknown provider, a missing or malformed
 * Stripe key, or a live key without the explicit compliance acknowledgement stops the application instead of silently
 * faking payments or silently taking real money. To run without payments at all, set {@code FUNDING_ENABLED=false}.
 */
@Configuration
public class PaymentProviderConfig {
    private static final Logger log = LoggerFactory.getLogger(PaymentProviderConfig.class);

    /** Tests register a fake {@link StripeGateway} bean; otherwise the SDK gateway is built from the key. */
    @Bean
    FundingRuntime fundingRuntime(FundingProperties props, ConnectProperties connect, ObjectMapper mapper, ObjectProvider<StripeGateway> gateways) {
        FundingRuntime runtime = select(props, connect, mapper, Clock.systemUTC(), key -> gateways.getIfAvailable(() -> new SdkStripeGateway(key)));
        if (runtime.enabled()) {
            // Only ids and modes are logged, never a key.
            log.info("Moderator funding enabled with provider '{}' (mode={})", runtime.provider().id(), runtime.provider().mode());
            if (runtime.provider() instanceof StripePaymentProvider s && !s.webhooksConfigured()) {
                log.warn("STRIPE_WEBHOOK_SECRET is not set: Stripe webhooks will be refused. Contributions are still confirmed when the "
                        + "contributor returns from Checkout, but renewals, refunds and cancellations made in Stripe won't arrive.");
            }
        } else {
            log.info("Moderator funding disabled by configuration");
        }
        return runtime;
    }

    /** Stripe Connect payouts (D-046, D-047): on only when asked for, with Stripe as the provider, and never live without a second flag. */
    @Bean
    ConnectRuntime connectRuntime(FundingRuntime funding, FundingProperties props, ConnectProperties connect) {
        ConnectRuntime c = connect(funding, props, connect);
        if (c.enabled()) log.info("Stripe Connect payouts enabled (mode={}, country={})", c.mode(), c.country());
        return c;
    }

    /** Pure Connect selection logic, unit-tested without a Spring context. */
    public static ConnectRuntime connect(FundingRuntime funding, FundingProperties props, ConnectProperties connect) {
        if (!connect.on()) {
            if (connect.live()) throw new IllegalStateException("STRIPE_CONNECT_LIVE=true needs STRIPE_CONNECT_ENABLED=true; unset it");
            return ConnectRuntime.off("Payouts aren't enabled on this server yet. Your share is held and recorded; it will be paid out "
                    + "once the operator turns on Stripe Connect.");
        }
        if (!funding.enabled() || !(funding.provider() instanceof StripePaymentProvider stripe)) {
            throw new IllegalStateException("STRIPE_CONNECT_ENABLED=true needs FUNDING_ENABLED=true and FUNDING_PROVIDER=stripe (D-046)");
        }
        boolean live = Money.MODE_LIVE.equals(stripe.mode());
        if (live && !connect.live()) {
            throw new IllegalStateException("Stripe Connect with a live key sends real money to moderators. It also needs STRIPE_CONNECT_LIVE=true, "
                    + "on top of FUNDING_LIVE_MODE and FUNDING_COMPLIANCE_ACK (docs/DECISIONS.md D-047). For development use a test key.");
        }
        if (!live && connect.live()) {
            throw new IllegalStateException("STRIPE_CONNECT_LIVE=true but STRIPE_SECRET_KEY is a test key; unset STRIPE_CONNECT_LIVE for test mode");
        }
        String country = connect.countryOrDefault();
        if (!country.matches("[A-Z]{2}")) throw new IllegalStateException("STRIPE_CONNECT_COUNTRY must be a two-letter country code, like US");
        return ConnectRuntime.on(stripe.mode(), country, stripe.gateway());
    }

    /** Pure selection logic, unit-tested without a Spring context. */
    public static FundingRuntime select(FundingProperties props, ObjectMapper mapper, Clock clock, Function<String, StripeGateway> gatewayFactory) {
        return select(props, ConnectProperties.disabled(), mapper, clock, gatewayFactory);
    }

    public static FundingRuntime select(FundingProperties props, ConnectProperties connect, ObjectMapper mapper, Clock clock,
                                        Function<String, StripeGateway> gatewayFactory) {
        if (!props.isEnabled()) return FundingRuntime.disabled("Moderator funding is turned off on this server.");
        String id = props.providerId();
        if (DemoPaymentProvider.ID.equals(id)) {
            if (props.live()) throw new IllegalStateException("FUNDING_LIVE_MODE=true makes no sense with the demo provider; unset it");
            return FundingRuntime.of(new DemoPaymentProvider(props.demoWebhookSecret(), props.tolerance(), mapper, clock));
        }
        if (StripePaymentProvider.ID.equals(id)) {
            FundingProperties.Stripe stripe = props.stripeOrEmpty();
            String mode = StripePaymentProvider.modeOfKey(stripe.key());
            if (Money.MODE_LIVE.equals(mode)) {
                boolean acked = props.complianceAck() != null && !props.complianceAck().isBlank();
                if (!props.live() || !acked) {
                    throw new IllegalStateException("STRIPE_SECRET_KEY is a live key. The server refuses to take real money unless FUNDING_LIVE_MODE=true "
                            + "and FUNDING_COMPLIANCE_ACK records the compliance sign-off (KYC of recipients, payouts, tax reporting, money "
                            + "transmission; docs/DECISIONS.md D-034). For development use a test key (sk_test_...).");
                }
                if (stripe.hookSecret().isEmpty()) {
                    throw new IllegalStateException("Live Stripe mode needs STRIPE_WEBHOOK_SECRET: refunds, renewals and cancellations arrive by webhook");
                }
            } else if (props.live()) {
                throw new IllegalStateException("FUNDING_LIVE_MODE=true but STRIPE_SECRET_KEY is a test key; unset FUNDING_LIVE_MODE for test mode");
            }
            return FundingRuntime.of(new StripePaymentProvider(gatewayFactory.apply(stripe.key()), mode, stripe.hookSecret(),
                    connect.hookSecret(), props.tolerance(), mapper, clock));
        }
        throw new IllegalStateException("howlers.funding.provider='" + id + "' is not implemented. Use 'demo' (simulated money) or 'stripe' "
                + "(docs/DECISIONS.md D-034), or FUNDING_ENABLED=false to turn funding off.");
    }
}
