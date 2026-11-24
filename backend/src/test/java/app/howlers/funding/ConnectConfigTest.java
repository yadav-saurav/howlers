package app.howlers.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.funding.payment.ConnectProperties;
import app.howlers.funding.payment.ConnectRuntime;
import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProviderConfig;
import app.howlers.funding.payment.StripeGateway.ConnectedAccount;
import app.howlers.funding.payment.StripePaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** When Connect payouts may run (D-047), and the pure status rules. No Spring, no network. */
class ConnectConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeStripeGateway gateway = new FakeStripeGateway();

    private FundingRuntime stripe(String mode) {
        return FundingRuntime.of(new StripePaymentProvider(gateway, mode, "whsec_x_0123456789", 300, mapper, Clock.systemUTC()));
    }

    private static final FundingProperties PROPS = FundingProperties.of(true, "stripe", null);

    @Test
    void offByDefaultWithAnHonestReason() {
        ConnectRuntime c = PaymentProviderConfig.connect(stripe("test"), PROPS, new ConnectProperties(null, null, null, null));
        assertThat(c.enabled()).isFalse();
        assertThat(c.disabledReason()).contains("Payouts aren't enabled on this server");
        assertThatThrownBy(c::require).hasMessageContaining("Payouts aren't enabled");
    }

    @Test
    void testModeConnectNeedsStripe() {
        ConnectRuntime c = PaymentProviderConfig.connect(stripe("test"), PROPS, new ConnectProperties(true, false, "gb", null));
        assertThat(c.enabled()).isTrue();
        assertThat(c.mode()).isEqualTo("test");
        assertThat(c.country()).isEqualTo("GB");
        FundingRuntime demo = PaymentProviderConfig.select(FundingProperties.of(true, "demo", "0123456789abcdef0123"), mapper, Clock.systemUTC(),
                k -> gateway);
        assertThatThrownBy(() -> PaymentProviderConfig.connect(demo, PROPS, new ConnectProperties(true, false, null, null)))
                .hasMessageContaining("FUNDING_PROVIDER=stripe");
        assertThatThrownBy(() -> PaymentProviderConfig.connect(stripe("test"), PROPS, new ConnectProperties(true, false, "USA", null)))
                .hasMessageContaining("two-letter");
    }

    @Test
    void liveConnectNeedsItsOwnFlagAndATestKeyRefusesIt() {
        assertThatThrownBy(() -> PaymentProviderConfig.connect(stripe("live"), PROPS, new ConnectProperties(true, false, null, null)))
                .hasMessageContaining("STRIPE_CONNECT_LIVE=true");
        assertThat(PaymentProviderConfig.connect(stripe("live"), PROPS, new ConnectProperties(true, true, null, null)).mode()).isEqualTo("live");
        assertThatThrownBy(() -> PaymentProviderConfig.connect(stripe("test"), PROPS, new ConnectProperties(true, true, null, null)))
                .hasMessageContaining("test key");
        assertThatThrownBy(() -> PaymentProviderConfig.connect(stripe("test"), PROPS, new ConnectProperties(false, true, null, null)))
                .hasMessageContaining("STRIPE_CONNECT_ENABLED");
    }

    @Test
    void secretsNeverPrint() {
        assertThat(new ConnectProperties(true, false, null, "whsec_secret_value").toString()).doesNotContain("whsec").contains("***");
    }

    @Test
    void accountStatusRules() {
        assertThat(ConnectService.statusOf(new ConnectedAccount("acct_1", false, false, false, "inactive", 5, "requirements.past_due")))
                .isEqualTo("PENDING");
        assertThat(ConnectService.statusOf(new ConnectedAccount("acct_1", true, false, false, "pending", 0, null))).isEqualTo("PENDING");
        assertThat(ConnectService.statusOf(new ConnectedAccount("acct_1", true, false, false, "inactive", 2, null))).isEqualTo("REQUIREMENTS_DUE");
        assertThat(ConnectService.statusOf(new ConnectedAccount("acct_1", true, true, true, "active", 0, null))).isEqualTo("ENABLED");
        // Payouts enabled but transfers not active yet: money can't be sent.
        assertThat(ConnectService.statusOf(new ConnectedAccount("acct_1", true, true, true, "pending", 0, null))).isEqualTo("PENDING");
        // From a webhook's JSON.
        assertThat(ConnectService.account(Map.of("id", "acct_2", "details_submitted", true, "payouts_enabled", true,
                "capabilities", Map.of("transfers", "active"), "requirements", Map.of("currently_due", java.util.List.of()))).transfersCapability())
                .isEqualTo("active");
    }

    @Test
    void labelsSayTestModeAndWhatToDo() {
        assertThat(PayoutService.label("PAID", "test", 0, true, "ENABLED")).isEqualTo("Paid out (test mode)");
        assertThat(PayoutService.label("PAID", "live", 0, true, "ENABLED")).isEqualTo("Paid out");
        assertThat(PayoutService.label("HELD", "test", 0, true, "PENDING")).isEqualTo("Held: connect your account");
        assertThat(PayoutService.label("HELD", "test", 0, true, null)).isEqualTo("Held: connect your account");
        assertThat(PayoutService.label("HELD", "test", 0, false, null)).isEqualTo("Held: payouts not enabled yet");
        assertThat(PayoutService.fmt(1_234, "USD", "test")).isEqualTo("12.34 USD (test mode)");
        assertThat(PayoutService.fmt(5, "JPY", "live")).isEqualTo("5 JPY");
    }
}
