package app.howlers.funding.payment;

import app.howlers.funding.Money;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Demo payments: every charge succeeds instantly, moves no money and is marked {@code demo = true}.
 *
 * <p>Webhooks use the same shape a real processor would: header {@code t=<unix seconds>,v1=<hex HMAC-SHA256>} over
 * {@code "<t>.<raw body>"} with a shared secret from configuration. The signature is compared in constant time and the
 * timestamp must be recent, so captured requests can't be replayed later; the event id is additionally de-duplicated
 * by the caller (funding_webhook_events).
 */
public class DemoPaymentProvider implements PaymentProvider {
    public static final String ID = "demo";
    public static final String SIGNATURE_HEADER = "Howlers-Demo-Signature";

    private final byte[] secret;
    private final long toleranceSeconds;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DemoPaymentProvider(String secret, long toleranceSeconds, ObjectMapper mapper, Clock clock) {
        if (secret == null || secret.strip().length() < 16) {
            throw new IllegalStateException("howlers.funding.demo-webhook-secret must be at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String mode() {
        return Money.MODE_DEMO;
    }

    @Override
    public String notice() {
        return "Demo mode: contributions are simulated, always succeed, and no real money moves. Nothing is charged and nobody is paid.";
    }

    @Override
    public String signatureHeader() {
        return SIGNATURE_HEADER;
    }

    @Override
    public Charge createCharge(ChargeRequest request) {
        if (request.amountMinor() <= 0) throw new IllegalArgumentException("amount must be positive");
        // Deterministic per idempotency key, like a real provider returning the same charge for a retried request.
        String id = "demo_ch_" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new Charge(id, true, true);
    }

    @Override
    public Subscription createSubscription(SubscriptionRequest request) {
        if (request.amountMinor() <= 0) throw new IllegalArgumentException("amount must be positive");
        String id = "demo_sub_" + UUID.nameUUIDFromBytes(request.idempotencyKey().getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new Subscription(id, true);
    }

    @Override
    public void cancel(String subscriptionRef) {
        // Nothing to call: demo subscriptions exist only in Howlers' own tables.
    }

    @Override
    public WebhookEvent verifyWebhook(String payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookVerificationException("Missing signature");
        }
        Long t = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    throw new WebhookVerificationException("Malformed timestamp");
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) throw new WebhookVerificationException("Malformed signature header");
        byte[] given;
        try {
            given = HexFormat.of().parseHex(v1);
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("Malformed signature");
        }
        byte[] expected = hmac(secret, t + "." + payload);
        // Constant-time comparison: timing must not reveal how many leading bytes matched.
        if (!MessageDigest.isEqual(expected, given)) throw new WebhookVerificationException("Signature mismatch");
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - t) > toleranceSeconds) throw new WebhookVerificationException("Timestamp outside tolerance");

        Map<String, Object> body;
        try {
            body = mapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new WebhookVerificationException("Payload is not JSON");
        }
        Object id = body.get("id"), type = body.get("type"), data = body.get("data");
        if (!(id instanceof String sid) || sid.isBlank() || sid.length() > 100 || !(type instanceof String st) || st.isBlank()) {
            throw new WebhookVerificationException("Event needs an id and a type");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> d = data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new WebhookEvent(sid, st, d);
    }

    /** Builds the signature header for a payload. Used by tests and by anyone simulating demo webhooks (docs/API.md). */
    public static String signatureHeader(String secret, String payload, long epochSeconds) {
        return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), epochSeconds + "." + payload));
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
