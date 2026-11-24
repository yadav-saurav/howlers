package app.howlers.funding;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds Stripe-shaped event payloads and signs them with Stripe's documented scheme, independently of the SDK. */
final class StripeEvents {
    private StripeEvents() {}

    /**
     * {@code Stripe-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(webhook secret, "<t>.<raw body>")>}
     * (https://docs.stripe.com/webhooks#verify-manually). The whole {@code whsec_...} string is the key.
     */
    static String sign(String secret, String payload, long epochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = HexFormat.of().formatHex(mac.doFinal((epochSeconds + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + epochSeconds + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String event(String id, String type, boolean livemode, String objectJson) {
        return """
                {"id":"%s","object":"event","api_version":"2025-10-29.clover","created":1764000000,"livemode":%s,"pending_webhooks":1,\
                "request":{"id":null,"idempotency_key":null},"type":"%s","data":{"object":%s}}""".formatted(id, livemode, type, objectJson);
    }

    static String session(String id, String mode, String status, String paymentStatus, long pendingId, long amount, String currency,
                          String paymentIntent, String subscription, String invoice) {
        return """
                {"id":"%s","object":"checkout.session","mode":"%s","status":"%s","payment_status":"%s","client_reference_id":"%d",\
                "amount_total":%d,"currency":"%s","livemode":false,"payment_intent":%s,"subscription":%s,"invoice":%s,\
                "metadata":{"howlers_contribution_id":"%d","howlers_anonymous":"true"}}""".formatted(id, mode, status, paymentStatus, pendingId,
                amount, currency, q(paymentIntent), q(subscription), q(invoice), pendingId);
    }

    /** A 2025+ API invoice: the subscription and its metadata sit under parent.subscription_details. */
    static String invoice(String id, String subscription, long amountPaid, String currency, Long pendingId) {
        String meta = pendingId == null ? "{}" : "{\"howlers_contribution_id\":\"" + pendingId + "\"}";
        return """
                {"id":"%s","object":"invoice","amount_paid":%d,"currency":"%s","livemode":false,"status":"paid",\
                "parent":{"type":"subscription_details","subscription_details":{"subscription":"%s","metadata":%s}}}""".formatted(id,
                amountPaid, currency, subscription, meta);
    }

    static String charge(String id, String paymentIntent, long amount, long amountRefunded, String currency) {
        return """
                {"id":"%s","object":"charge","amount":%d,"amount_refunded":%d,"currency":"%s","livemode":false,"refunded":%s,\
                "payment_intent":%s}""".formatted(id, amount, amountRefunded, currency, amountRefunded >= amount, q(paymentIntent));
    }

    static String subscription(String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"status\":\"canceled\",\"livemode\":false}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
}
