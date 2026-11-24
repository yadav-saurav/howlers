package app.howlers.funding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.common.error.ApiException;
import app.howlers.funding.payment.DemoPaymentProvider;
import app.howlers.funding.payment.FundingRuntime;
import app.howlers.funding.payment.PaymentProvider.ChargeRequest;
import app.howlers.funding.payment.PaymentProvider.WebhookVerificationException;
import app.howlers.funding.payment.PaymentProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Allocation arithmetic, demo webhook signatures and provider selection, without Spring. */
class FundingUnitTest {
    private static final String SECRET = "unit-test-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final DemoPaymentProvider demo = new DemoPaymentProvider(SECRET, 300, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void equalSplitHandsOutEveryMinorUnit() {
        Map<Long, Long> split = AllocationMath.equal(1001, List.of(7L, 3L, 5L));
        assertThat(split.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1001);
        assertThat(split).containsEntry(3L, 334L).containsEntry(5L, 334L).containsEntry(7L, 333L);
        assertThat(AllocationMath.equal(0, List.of(1L, 2L))).containsOnly(Map.entry(1L, 0L), Map.entry(2L, 0L));
        assertThat(AllocationMath.equal(100, List.of())).isEmpty();
    }

    @Test
    void weightedSplitUsesLargestRemainders() {
        Map<Long, Long> w = new LinkedHashMap<>();
        w.put(1L, 1L);
        w.put(2L, 1L);
        w.put(3L, 1L);
        w.put(4L, 0L);
        Map<Long, Long> split = AllocationMath.weighted(100, w);
        assertThat(split).containsEntry(1L, 34L).containsEntry(2L, 33L).containsEntry(3L, 33L).containsEntry(4L, 0L);

        Map<Long, Long> uneven = new LinkedHashMap<>();
        uneven.put(10L, 3L);
        uneven.put(20L, 2L);
        assertThat(AllocationMath.weighted(1000, uneven)).containsEntry(10L, 600L).containsEntry(20L, 400L);
        assertThat(AllocationMath.weighted(7, uneven).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(7);
        assertThat(AllocationMath.weighted(100, Map.of(1L, 0L))).isEmpty();
        assertThatThrownBy(() -> AllocationMath.weighted(100, Map.of(1L, -1L))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activityScoreIsCappedPerComponent() {
        assertThat(AllocationMath.activityScore(3, 1, 50)).isEqualTo(5);
        assertThat(AllocationMath.activityScore(500, 0, 50)).isEqualTo(50);
        assertThat(AllocationMath.activityScore(500, 500, 50)).isEqualTo(100);
        assertThat(AllocationMath.activityScore(0, 0, 50)).isZero();
    }

    @Test
    void demoChargesAreDemoAndIdempotentPerKey() {
        var a = demo.createCharge(new ChargeRequest(500, "USD", "x", "k1"));
        var b = demo.createCharge(new ChargeRequest(500, "USD", "x", "k1"));
        var c = demo.createCharge(new ChargeRequest(500, "USD", "x", "k2"));
        assertThat(a.demo()).isTrue();
        assertThat(a.succeeded()).isTrue();
        assertThat(a.chargeId()).isEqualTo(b.chargeId()).isNotEqualTo(c.chargeId());
    }

    @Test
    void webhookSignatureIsVerified() {
        String payload = "{\"id\":\"evt_1\",\"type\":\"charge.succeeded\",\"data\":{\"chargeId\":\"demo_ch_x\"}}";
        String header = DemoPaymentProvider.signatureHeader(SECRET, payload, NOW.getEpochSecond());
        var event = demo.verifyWebhook(payload, header);
        assertThat(event.id()).isEqualTo("evt_1");
        assertThat(event.type()).isEqualTo("charge.succeeded");
        assertThat(event.data()).containsEntry("chargeId", "demo_ch_x");

        assertThatThrownBy(() -> demo.verifyWebhook(payload, null)).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> demo.verifyWebhook(payload, "garbage")).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> demo.verifyWebhook(payload, "t=1,v1=zz")).isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> demo.verifyWebhook(payload + " ", header)).hasMessageContaining("mismatch");
        assertThatThrownBy(() -> demo.verifyWebhook(payload, DemoPaymentProvider.signatureHeader("wrong-secret-0123456789", payload, NOW.getEpochSecond())))
                .hasMessageContaining("mismatch");
        String old = DemoPaymentProvider.signatureHeader(SECRET, payload, NOW.getEpochSecond() - 301);
        assertThatThrownBy(() -> demo.verifyWebhook(payload, old)).hasMessageContaining("tolerance");
        String noId = "{\"type\":\"x\"}";
        assertThatThrownBy(() -> demo.verifyWebhook(noId, DemoPaymentProvider.signatureHeader(SECRET, noId, NOW.getEpochSecond())))
                .hasMessageContaining("id");
    }

    @Test
    void unknownProviderRefusesToStartAndDisabledFundingRefusesWrites() {
        assertThatThrownBy(() -> PaymentProviderConfig.select(FundingProperties.of(true, "paypal", SECRET), mapper, Clock.systemUTC(), k -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not implemented");
        assertThatThrownBy(() -> PaymentProviderConfig.select(FundingProperties.of(true, "stripe", SECRET), mapper, Clock.systemUTC(), k -> null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> PaymentProviderConfig.select(FundingProperties.of(true, "demo", "short"), mapper, Clock.systemUTC(), k -> null))
                .isInstanceOf(IllegalStateException.class);

        FundingRuntime off = PaymentProviderConfig.select(FundingProperties.of(false, "stripe", null), mapper, Clock.systemUTC(), k -> null);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(off::require).isInstanceOf(ApiException.class).hasMessageContaining("turned off");

        FundingRuntime on = PaymentProviderConfig.select(FundingProperties.of(null, null, SECRET), mapper, Clock.systemUTC(), k -> null);
        assertThat(on.provider().id()).isEqualTo("demo");
        assertThat(on.provider().demo()).isTrue();
        assertThat(on.provider().mode()).isEqualTo("demo");
        assertThat(on.provider().label()).isEqualTo(Money.DEMO_LABEL);
        assertThat(on.provider().hostedCheckout()).isFalse();
    }

    @Test
    void moneyLimitsAreIntegerMinorUnits() {
        assertThat(Money.unit("USD")).isEqualTo(100);
        assertThat(Money.unit("JPY")).isEqualTo(1);
        assertThat(Money.maxContribution("EUR")).isEqualTo(1_000_000);
        assertThat(Money.of(5, "USD", "demo").label()).isEqualTo(Money.DEMO_LABEL);
        assertThat(Money.of(5, "USD", "test").label()).isEqualTo("TEST MODE: no real money");
        assertThat(Money.of(5, "USD", "test").demo()).isTrue();
        assertThat(Money.of(5, "USD", "live").label()).isNull();
        assertThat(Money.of(5, "USD", "live").demo()).isFalse();
        assertThat(Money.supported("XYZ")).isFalse();
    }
}
