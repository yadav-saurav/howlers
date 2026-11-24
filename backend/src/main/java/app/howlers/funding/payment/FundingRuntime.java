package app.howlers.funding.payment;

import app.howlers.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** The active payment provider, or the reason funding is disabled. Exactly one of the two is set. */
public record FundingRuntime(PaymentProvider provider, String disabledReason) {

    public static FundingRuntime of(PaymentProvider provider) {
        return new FundingRuntime(provider, null);
    }

    public static FundingRuntime disabled(String reason) {
        return new FundingRuntime(null, reason);
    }

    public boolean enabled() {
        return provider != null;
    }

    /** The provider for a write, or 503 FUNDING_DISABLED. */
    public PaymentProvider require() {
        if (provider == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDING_DISABLED", disabledReason);
        return provider;
    }
}
