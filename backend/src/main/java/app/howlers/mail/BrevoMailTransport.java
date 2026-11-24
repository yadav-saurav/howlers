package app.howlers.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Brevo's transactional email REST API ({@code POST /v3/smtp/email}, D-038) over the JDK {@link HttpClient}.
 *
 * <p>Tracking: Brevo has no per-message switch for click tracking or for the open pixel on ordinary plans. Each
 * recipient is sent with {@code contactPixelTrackingConsent: false}, which anonymises opens and clicks where the
 * account has per-contact consent enabled; {@code removeTrackingPixel: true} (Enterprise only) is added when
 * {@code BREVO_REMOVE_TRACKING_PIXEL=true}. Our templates contain no pixel of their own.
 */
public class BrevoMailTransport implements MailTransport {
    static final String PATH = "/v3/smtp/email";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_CHARS = 300;

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final String replyTo;
    private final boolean removeTrackingPixel;
    private final Duration timeout;

    public BrevoMailTransport(HttpClient http, String baseUrl, String apiKey, String fromAddress, String fromName,
                              String replyTo, boolean removeTrackingPixel, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("MAIL_MODE=brevo needs BREVO_API_KEY");
        if (fromAddress == null || fromAddress.isBlank()) throw new IllegalStateException("MAIL_MODE=brevo needs MAIL_FROM_ADDRESS");
        this.http = http;
        this.endpoint = URI.create(stripTrailingSlash(baseUrl == null || baseUrl.isBlank() ? "https://api.brevo.com" : baseUrl) + PATH);
        this.apiKey = apiKey.trim();
        this.fromAddress = fromAddress.trim();
        this.fromName = fromName;
        this.replyTo = replyTo;
        this.removeTrackingPixel = removeTrackingPixel;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    @Override
    public String name() { return "brevo"; }

    /** The JSON body Brevo receives. Jackson does the string escaping; the HTML is already escaped by the templates. */
    String payload(OutgoingEmail email) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode sender = root.putObject("sender");
        sender.put("email", fromAddress);
        if (fromName != null && !fromName.isBlank()) sender.put("name", truncate(fromName, 70));
        ArrayNode to = root.putArray("to");
        ObjectNode recipient = to.addObject();
        recipient.put("email", email.to());
        if (email.toName() != null && !email.toName().isBlank()) recipient.put("name", truncate(email.toName(), 70));
        recipient.put("contactPixelTrackingConsent", false);
        if (removeTrackingPixel) recipient.put("removeTrackingPixel", true);
        if (replyTo != null && !replyTo.isBlank()) root.putObject("replyTo").put("email", replyTo.trim());
        root.put("subject", email.subject());
        root.put("htmlContent", email.html());
        root.put("textContent", email.text());
        root.putArray("tags").add(email.tag());
        // Spelled as in Brevo's API reference example; kept constant across retries of the same message.
        root.putObject("headers").put("IdempotencyKey", email.idempotencyKey());
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    HttpRequest request(OutgoingEmail email) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(email), StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public String send(OutgoingEmail email) throws MailDeliveryException {
        HttpResponse<String> res;
        try {
            res = http.send(request(email), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw MailDeliveryException.retryable("Brevo unreachable: " + e.getClass().getSimpleName(), 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw MailDeliveryException.permanent("Interrupted while sending", 0);
        }
        int status = res.statusCode();
        if (status == 201 || status == 202 || status == 200) return messageId(res.body());
        String detail = errorDetail(res.body());
        if (status == 429) {
            throw MailDeliveryException.retryable("Brevo rate limit (429)" + detail, status, retryAfter(res));
        }
        if (status >= 500) {
            throw MailDeliveryException.retryable("Brevo server error (" + status + ")" + detail, status, retryAfter(res));
        }
        if (status == 400 && res.body() != null && res.body().contains("duplicate_request")) {
            // An earlier attempt with the same idempotency key was accepted; the mail is on its way.
            return null;
        }
        if (status == 401 || status == 403) {
            throw MailDeliveryException.permanent("Brevo rejected the API key (" + status + "); check BREVO_API_KEY"
                    + " and the account's authorised IPs" + detail, status);
        }
        throw MailDeliveryException.permanent("Brevo refused the message (" + status + ")" + detail
                + "; the sender " + fromAddress + " must be a verified sender or on a verified domain", status);
    }

    private static String messageId(String body) {
        try {
            JsonNode n = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            return n.hasNonNull("messageId") ? n.get("messageId").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Brevo's {@code {code, message}} error, shortened. It describes the request, never echoes the key. */
    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode n = JSON.readTree(body);
            String code = n.path("code").asText("");
            String message = n.path("message").asText("");
            String s = (code + (code.isEmpty() || message.isEmpty() ? "" : ": ") + message).trim();
            return s.isEmpty() ? "" : " " + truncate(s, MAX_ERROR_CHARS);
        } catch (IOException e) {
            return "";
        }
    }

    static Duration retryAfter(HttpResponse<?> res) {
        Optional<String> v = res.headers().firstValue("retry-after").or(() -> res.headers().firstValue("x-sib-ratelimit-reset"));
        if (v.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(v.get().trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
