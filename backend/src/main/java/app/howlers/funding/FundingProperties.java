package app.howlers.funding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Moderator funding configuration ({@code howlers.funding.*}).
 *
 * @param enabled            false turns the whole feature off (reads still work, every write is refused)
 * @param provider           payment provider id: {@code demo} (default) or {@code stripe}. Anything else refuses to start
 * @param demoWebhookSecret  HMAC secret the demo provider signs and verifies webhooks with (>= 16 chars)
 * @param webhookToleranceSeconds how old a signed webhook timestamp may be before it's rejected
 * @param jobsEnabled        runs the recurring-charge and period-close jobs on a schedule
 * @param liveMode           must be true (with {@code complianceAck}) before a live Stripe key is accepted (D-034)
 * @param complianceAck      free text recording who signed off the compliance review for real money, and when
 * @param stripe             Stripe keys; read from the environment or the git-ignored backend/.env.local, never logged
 */
@ConfigurationProperties(prefix = "howlers.funding")
public record FundingProperties(Boolean enabled, String provider, String demoWebhookSecret, Long webhookToleranceSeconds,
                                Boolean jobsEnabled, Boolean liveMode, String complianceAck, Stripe stripe) {

    /** Demo-style settings without Stripe or live mode (tests). */
    public static FundingProperties of(Boolean enabled, String provider, String demoWebhookSecret) {
        return new FundingProperties(enabled, provider, demoWebhookSecret, null, null, null, null, null);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String providerId() {
        return provider == null || provider.isBlank() ? "demo" : provider.strip().toLowerCase();
    }

    public long tolerance() {
        return webhookToleranceSeconds == null ? 300 : webhookToleranceSeconds;
    }

    public boolean live() {
        return Boolean.TRUE.equals(liveMode);
    }

    public Stripe stripeOrEmpty() {
        return stripe == null ? new Stripe(null, null) : stripe;
    }

    /**
     * @param secretKey     {@code sk_test_...} / {@code rk_test_...} (test mode) or {@code sk_live_...} (refused unless live mode is acknowledged)
     * @param webhookSecret {@code whsec_...} from the dashboard endpoint or from {@code stripe listen}
     */
    public record Stripe(String secretKey, String webhookSecret) {
        public String key() {
            return secretKey == null ? "" : secretKey.strip();
        }

        public String hookSecret() {
            return webhookSecret == null ? "" : webhookSecret.strip();
        }

        /** Never prints the keys, only whether they are set. */
        @Override
        public String toString() {
            return "Stripe[secretKey=" + (key().isEmpty() ? "unset" : "***") + ", webhookSecret=" + (hookSecret().isEmpty() ? "unset" : "***") + "]";
        }
    }

    @Override
    public String toString() {
        return "FundingProperties[enabled=" + isEnabled() + ", provider=" + providerId() + ", liveMode=" + live() + ", stripe=" + stripeOrEmpty() + "]";
    }
}
