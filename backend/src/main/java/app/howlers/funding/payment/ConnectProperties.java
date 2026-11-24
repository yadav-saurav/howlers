package app.howlers.funding.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe Connect payouts to moderators ({@code howlers.funding.connect.*}, D-046, D-047).
 *
 * @param enabled       true turns on onboarding and transfers. Needs {@code FUNDING_PROVIDER=stripe}, and Connect activated
 *                      in the Stripe dashboard. Off by default: allocations of Stripe pools are then held, and the UI says so
 * @param liveMode      must also be true before Connect runs with a live key (on top of D-034's live-mode acknowledgement)
 * @param country       the country new Express accounts are created in (ISO 3166-1 alpha-2)
 * @param webhookSecret signing secret of a separate "connected accounts" endpoint in the dashboard, when there is one
 *                      ({@code stripe listen --forward-connect-to} signs both kinds with the same secret)
 */
@ConfigurationProperties(prefix = "howlers.funding.connect")
public record ConnectProperties(Boolean enabled, Boolean liveMode, String country, String webhookSecret) {

    public static ConnectProperties disabled() {
        return new ConnectProperties(false, false, null, null);
    }

    public boolean on() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean live() {
        return Boolean.TRUE.equals(liveMode);
    }

    public String countryOrDefault() {
        return country == null || country.isBlank() ? "US" : country.strip().toUpperCase();
    }

    public String hookSecret() {
        return webhookSecret == null ? "" : webhookSecret.strip();
    }

    /** Never prints the secret, only whether it is set. */
    @Override
    public String toString() {
        return "ConnectProperties[enabled=" + on() + ", liveMode=" + live() + ", country=" + countryOrDefault() + ", webhookSecret="
                + (hookSecret().isEmpty() ? "unset" : "***") + "]";
    }
}
