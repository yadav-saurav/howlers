package app.howlers.push;

import app.howlers.common.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Web push (D-040): device subscriptions and delivery. Messages are encrypted per device (RFC 8291) and signed with
 * the server's VAPID key (RFC 8292), then POSTed to the browser's push service. Only allow-listed push service hosts
 * are ever contacted, redirects are not followed, and nothing beyond what an in-app notification shows is sent.
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    public enum Category { REPLIES, MENTIONS, MESSAGES, MODERATION, FOLLOWERS, ACTIVITY }

    public static final List<String> DEFAULT_CATEGORIES = List.of("REPLIES", "MENTIONS", "MESSAGES", "MODERATION");
    private static final int MAX_DEVICES = 10;

    private final JdbcClient jdbc;
    private final PushProperties props;
    private final ObjectMapper mapper;
    private volatile HttpClient http;
    private volatile CachedVapid cached;

    private record CachedVapid(String pub, String priv, String subject, Vapid vapid, String error) {}

    public PushService(JdbcClient jdbc, PushProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.props = props;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ configuration

    /** The configured VAPID pair, or empty when push isn't set up (or the keys are invalid; logged once, no key values). */
    public Optional<Vapid> vapid() {
        CachedVapid c = cached;
        String pub = props.getVapidPublicKey(), priv = props.getVapidPrivateKey(), sub = props.getVapidSubject();
        if (c == null || !c.pub.equals(pub) || !c.priv.equals(priv) || !c.subject.equals(sub)) {
            Vapid v = null;
            String error = null;
            if (pub.isEmpty() || priv.isEmpty()) {
                error = "Push notifications aren't set up on this server (no VAPID keys configured).";
            } else {
                try {
                    v = Vapid.of(pub, priv, sub);
                } catch (IllegalArgumentException e) {
                    error = "Push notifications are switched off: the server's VAPID configuration is invalid.";
                    log.warn("Web push disabled: {}", e.getMessage());
                }
            }
            c = new CachedVapid(pub, priv, sub, v, error);
            cached = c;
        }
        return Optional.ofNullable(c.vapid);
    }

    public record PushConfig(boolean available, String publicKey, String unavailableReason, List<String> categories,
                             List<String> defaultCategories) {}

    public PushConfig config() {
        Optional<Vapid> v = vapid();
        return new PushConfig(v.isPresent(), v.map(Vapid::publicKey).orElse(null), v.isPresent() ? null : cached.error,
                Arrays.stream(Category.values()).map(Enum::name).toList(), DEFAULT_CATEGORIES);
    }

    private Vapid requireVapid() {
        return vapid().orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "PUSH_UNAVAILABLE", cached.error));
    }

    /** Push endpoints must be HTTPS on an allow-listed push service (or the local fake service in tests). */
    URI checkEndpoint(String endpoint) {
        URI u;
        try {
            u = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.field("endpoint", "Not a valid push endpoint");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean local = props.isAllowInsecureLocalhost() && "http".equals(u.getScheme())
                && (host.equals("127.0.0.1") || host.equals("localhost"));
        boolean allowed = "https".equals(u.getScheme()) && u.getUserInfo() == null
                && props.getAllowedHosts().stream().map(h -> h.toLowerCase(Locale.ROOT).trim())
                .anyMatch(h -> !h.isEmpty() && (host.equals(h) || host.endsWith("." + h)));
        if (!(local || allowed)) {
            throw ApiException.field("endpoint", "This browser's push service isn't supported by this server");
        }
        return u;
    }

    // ------------------------------------------------------------------ subscriptions

    public record Device(long id, String label, List<String> categories, Instant createdAt, Instant lastUsedAt) {}

    private static final String DEVICE_COLUMNS = "id, label, categories, created_at, last_used_at";

    private static Device device(java.sql.ResultSet rs) throws SQLException {
        OffsetDateTime used = rs.getObject("last_used_at", OffsetDateTime.class);
        return new Device(rs.getLong("id"), rs.getString("label"), list(rs.getArray("categories")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), used == null ? null : used.toInstant());
    }

    private static List<String> list(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    public List<Device> devices(long userId) {
        return jdbc.sql("SELECT " + DEVICE_COLUMNS + " FROM push_subscriptions WHERE user_id = ? ORDER BY created_at DESC, id DESC")
                .param(userId).query((rs, i) -> device(rs)).list();
    }

    /**
     * Saves (or refreshes) this browser's subscription for {@code userId}. The endpoint is unique: if another account
     * had registered the same browser, the row moves to this user, so the previous account stops receiving pushes here.
     */
    public Device subscribe(long userId, String endpoint, String p256dh, String auth, String label, List<String> categories) {
        requireVapid();
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 1024) throw ApiException.field("endpoint", "Missing push endpoint");
        checkEndpoint(endpoint);
        byte[] key, secret;
        try {
            key = WebPushCrypto.b64(p256dh == null ? "" : p256dh);
            secret = WebPushCrypto.b64(auth == null ? "" : auth);
            WebPushCrypto.publicKey(key);
        } catch (IllegalArgumentException e) {
            throw ApiException.field("keys", "The browser's subscription keys are invalid");
        }
        if (secret.length != 16) throw ApiException.field("keys", "The browser's subscription keys are invalid");
        String cleanLabel = cleanLabel(label);
        String[] cats = categories == null ? DEFAULT_CATEGORIES.toArray(String[]::new) : normalize(categories);
        Device d = jdbc.sql("""
                        INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, label, categories)
                        VALUES (:u, :e, :k, :a, :l, :c)
                        ON CONFLICT (endpoint) DO UPDATE SET
                            user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, label = EXCLUDED.label,
                            categories = CASE WHEN push_subscriptions.user_id = EXCLUDED.user_id AND :keep
                                              THEN push_subscriptions.categories ELSE EXCLUDED.categories END,
                            failure_count = 0,
                            created_at = CASE WHEN push_subscriptions.user_id = EXCLUDED.user_id
                                              THEN push_subscriptions.created_at ELSE now() END
                        RETURNING""" + " " + DEVICE_COLUMNS)
                .param("u", userId).param("e", endpoint.trim()).param("k", WebPushCrypto.b64(key))
                .param("a", WebPushCrypto.b64(secret)).param("l", cleanLabel).param("c", cats)
                .param("keep", categories == null)
                .query((rs, i) -> device(rs)).single();
        // Keep the newest devices only; old browsers that never unsubscribed fall off.
        jdbc.sql("""
                DELETE FROM push_subscriptions WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM push_subscriptions WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?)""")
                .params(userId, userId, MAX_DEVICES).update();
        return d;
    }

    public Device updateCategories(long userId, long id, List<String> categories, String label) {
        if (categories == null && label == null) throw ApiException.badRequest("Nothing to update");
        int n = jdbc.sql("""
                        UPDATE push_subscriptions SET categories = COALESCE(CAST(:c AS TEXT[]), categories),
                                                      label = COALESCE(CAST(:l AS VARCHAR), label)
                        WHERE id = :id AND user_id = :u""")
                .param("c", categories == null ? null : normalize(categories))
                .param("l", label == null ? null : cleanLabel(label))
                .param("id", id).param("u", userId).update();
        if (n == 0) throw ApiException.notFound("Device");
        return jdbc.sql("SELECT " + DEVICE_COLUMNS + " FROM push_subscriptions WHERE id = ?").param(id)
                .query((rs, i) -> device(rs)).single();
    }

    public void unsubscribe(long userId, long id) {
        jdbc.sql("DELETE FROM push_subscriptions WHERE id = ? AND user_id = ?").params(id, userId).update();
    }

    /** Signing out removes this browser's subscription by endpoint, so the next person on it doesn't get your pushes. */
    public void unsubscribeEndpoint(long userId, String endpoint) {
        if (endpoint == null) return;
        jdbc.sql("DELETE FROM push_subscriptions WHERE endpoint = ? AND user_id = ?").params(endpoint.trim(), userId).update();
    }

    private static String[] normalize(List<String> categories) {
        Set<String> valid = Set.of(Arrays.stream(Category.values()).map(Enum::name).toArray(String[]::new));
        return categories.stream().map(c -> c == null ? "" : c.trim().toUpperCase(Locale.ROOT)).peek(c -> {
            if (!valid.contains(c)) throw ApiException.field("categories", "Unknown category " + c);
        }).distinct().toArray(String[]::new);
    }

    private static String cleanLabel(String label) {
        String l = label == null ? "" : label.replaceAll("\\p{Cntrl}", "").trim();
        if (l.isEmpty()) return "This device";
        return l.length() > 80 ? l.substring(0, 80) : l;
    }

    // ------------------------------------------------------------------ delivery

    /** What a device shows. Kept small: a title, the in-app notification text and a relative link. */
    public record PushMessage(String title, String body, String url, String tag, String type) {}

    public enum Outcome { DELIVERED, GONE, REJECTED, FAILED, UNAVAILABLE }

    public record Result(Outcome outcome, int status) {}

    record Target(long id, String endpoint, String p256dh, String auth) {}

    /** Devices of {@code userId} that want {@code category}. */
    List<Target> targets(long userId, Category category) {
        return jdbc.sql("""
                        SELECT s.id, s.endpoint, s.p256dh, s.auth FROM push_subscriptions s JOIN users u ON u.id = s.user_id
                        WHERE s.user_id = ? AND ? = ANY (s.categories) AND u.status = 'ACTIVE' AND u.deleted_at IS NULL""")
                .params(userId, category.name())
                .query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    /** Sends a test notification to one of the caller's devices, synchronously, and reports what the push service said. */
    public Result sendTest(long userId, long deviceId) {
        requireVapid();
        Target t = jdbc.sql("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE id = ? AND user_id = ?")
                .params(deviceId, userId)
                .query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Device"));
        return deliver(t, new PushMessage("Howlers", "Test notification: push works on this device.", "/settings?tab=preferences",
                "test", "TEST"), "normal");
    }

    Result deliver(Target t, PushMessage msg, String urgency) {
        Optional<Vapid> vapid = vapid();
        if (vapid.isEmpty()) return new Result(Outcome.UNAVAILABLE, 0);
        URI endpoint;
        try {
            endpoint = checkEndpoint(t.endpoint());
        } catch (ApiException e) {
            delete(t.id()); // e.g. the allow-list changed since it was saved
            return new Result(Outcome.REJECTED, 0);
        }
        byte[] body;
        try {
            body = WebPushCrypto.encrypt(json(msg), WebPushCrypto.b64(t.p256dh()), WebPushCrypto.b64(t.auth()));
        } catch (IllegalArgumentException e) {
            delete(t.id());
            return new Result(Outcome.REJECTED, 0);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(props.getRequestTimeout())
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", Long.toString(props.getTtl().toSeconds()))
                .header("Urgency", urgency)
                .header("Authorization", vapid.get().authorization(endpoint, Instant.now()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (msg.tag() != null) req.header("Topic", topic(msg.tag())); // replaces an undelivered message with the same tag
        int status;
        try {
            status = client().send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            failed(t.id());
            log.info("Web push to device {} failed: {}", t.id(), e.getClass().getSimpleName());
            return new Result(Outcome.FAILED, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, 0);
        }
        if (status >= 200 && status < 300) {
            jdbc.sql("UPDATE push_subscriptions SET last_used_at = now(), failure_count = 0 WHERE id = ?").param(t.id()).update();
            return new Result(Outcome.DELIVERED, status);
        }
        if (status == 404 || status == 410) { // the browser unsubscribed or the subscription expired
            delete(t.id());
            return new Result(Outcome.GONE, status);
        }
        failed(t.id());
        log.info("Web push to device {} answered HTTP {}", t.id(), status);
        return new Result(status == 429 || status >= 500 ? Outcome.FAILED : Outcome.REJECTED, status);
    }

    private void delete(long id) {
        jdbc.sql("DELETE FROM push_subscriptions WHERE id = ?").param(id).update();
    }

    private void failed(long id) {
        jdbc.sql("""
                UPDATE push_subscriptions SET failure_count = failure_count + 1, last_failure_at = now() WHERE id = ?""")
                .param(id).update();
        jdbc.sql("DELETE FROM push_subscriptions WHERE id = ? AND failure_count >= ?").params(id, props.getMaxFailures()).update();
    }

    private byte[] json(PushMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", msg.title());
        m.put("body", msg.body());
        m.put("url", msg.url());
        m.put("tag", msg.tag());
        m.put("type", msg.type());
        try {
            return mapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Topic header: at most 32 base64url characters, so hash the tag. */
    static String topic(String tag) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8));
            return WebPushCrypto.b64(h).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER).build();
                }
                c = http;
            }
        }
        return c;
    }
}
