package app.howlers.funding.payment;

import app.howlers.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Whether Stripe Connect payouts run on this server, and with which gateway. When off, {@code disabledReason} says why in
 * words the UI can show as is ("Payouts aren't enabled on this server ...").
 */
public record ConnectRuntime(boolean enabled, String mode, String country, StripeGateway gateway, String disabledReason) {

    public static ConnectRuntime on(String mode, String country, StripeGateway gateway) {
        return new ConnectRuntime(true, mode, country, gateway, null);
    }

    public static ConnectRuntime off(String reason) {
        return new ConnectRuntime(false, null, null, null, reason);
    }

    /** The gateway for a Connect call, or 503 CONNECT_DISABLED. */
    public StripeGateway require() {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONNECT_DISABLED", disabledReason);
        return gateway;
    }
}
