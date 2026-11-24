package app.howlers.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.messaging.DmService;
import app.howlers.mute.MuteService;
import app.howlers.notification.NotificationService;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The whole web push path against a local fake push service: subscribe, a reply notification and a DM are encrypted
 * and VAPID-signed, POSTed to the fake service, which decrypts them with the "browser's" private key and checks the
 * JWT. No network beyond 127.0.0.1. Delivery through a real browser push service (FCM, Mozilla, Apple) is not tested.
 */
class WebPushIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired PushService push;
    @Autowired PushNotifier notifier;
    @Autowired PushProperties props;
    @Autowired NotificationService notifications;
    @Autowired DmService dms;
    @Autowired MuteService mutes;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    record Received(String path, Map<String, String> headers, byte[] body) {}

    private HttpServer server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private KeyPair vapidKeys;
    private String base;

    /** A browser: its push subscription keys. */
    record Browser(String endpoint, KeyPair keys, byte[] auth) {
        String p256dh() {
            return WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) keys.getPublic()));
        }

        String authB64() {
            return WebPushCrypto.b64(auth);
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push/", ex -> {
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
            byte[] body = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            received.add(new Received(path, h, body));
            ex.sendResponseHeaders(statusByPath.getOrDefault(path, 201), -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/";
        vapidKeys = WebPushCrypto.generateKeyPair(); // throwaway, never written anywhere
        props.setVapidPublicKey(WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) vapidKeys.getPublic())));
        props.setVapidPrivateKey(WebPushCrypto.b64(WebPushCrypto.encode((ECPrivateKey) vapidKeys.getPrivate())));
        props.setVapidSubject("mailto:ops@howlers.test");
        props.setAllowInsecureLocalhost(true);
        notifier.reset();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        props.setVapidPublicKey("");
        props.setVapidPrivateKey("");
        props.setVapidSubject("");
        props.setAllowInsecureLocalhost(false);
        notifier.reset();
    }

    private AuthUser user(String prefix) {
        String name = prefix + (System.nanoTime() % 1_000_000);
        var me = auth.register(new RegisterRequest(name, name + "@push.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    private Browser browser(String id) {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return new Browser(base + id + System.nanoTime(), WebPushCrypto.generateKeyPair(), secret);
    }

    private PushService.Device subscribe(AuthUser u, Browser b, List<String> categories) {
        return push.subscribe(u.id(), b.endpoint(), b.p256dh(), b.authB64(), "Firefox on Linux", categories);
    }

    /** Decrypts what the fake push service received, as the browser would, and checks the VAPID signature. */
    private JsonNode open(Received r, Browser b) throws Exception {
        assertThat(r.headers().get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(r.headers().get("ttl")).isEqualTo("86400");
        String authz = r.headers().get("authorization");
        assertThat(authz).startsWith("vapid t=").contains(", k=" + props.getVapidPublicKey());
        String jwt = authz.substring("vapid t=".length(), authz.indexOf(','));
        String[] parts = jwt.split("\\.");
        assertThat(Vapid.verify((ECPublicKey) vapidKeys.getPublic(), parts[0] + "." + parts[1], WebPushCrypto.b64(parts[2]))).isTrue();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("aud").asText()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
        byte[] plain = WebPushCrypto.decrypt(r.body(), (ECPrivateKey) b.keys().getPrivate(),
                WebPushCrypto.encode((ECPublicKey) b.keys().getPublic()), b.auth());
        return mapper.readTree(new String(plain, StandardCharsets.UTF_8));
    }

    private Received next() throws InterruptedException {
        Received r = received.poll(10, TimeUnit.SECONDS);
        assertThat(r).as("a push reached the fake push service").isNotNull();
        return r;
    }

    private void assertNothingArrives() throws InterruptedException {
        assertThat(received.poll(700, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void configSaysUnavailableWithoutKeysAndSubscribingIsRefused() {
        props.setVapidPublicKey("");
        assertThat(push.config().available()).isFalse();
        assertThat(push.config().publicKey()).isNull();
        assertThat(push.config().unavailableReason()).contains("aren't set up");
        AuthUser ana = user("pa");
        assertThatThrownBy(() -> subscribe(ana, browser("x"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));

        props.setVapidPublicKey(WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic())));
        assertThat(push.config().available()).isFalse(); // mismatched pair: switched off, not half-working
        assertThat(push.config().unavailableReason()).contains("invalid");
    }

    @Test
    void onlyAllowListedPushServicesAndValidKeysCanBeSubscribed() {
        AuthUser ana = user("pb");
        Browser b = browser("ok");
        for (String bad : List.of("http://169.254.169.254/latest", "https://evil.example.com/push/x", "https://fcm.googleapis.com.evil.io/x",
                "ftp://fcm.googleapis.com/x", "https://user@fcm.googleapis.com/x")) {
            assertThatThrownBy(() -> push.subscribe(ana.id(), bad, b.p256dh(), b.authB64(), null, null))
                    .as(bad).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> push.subscribe(ana.id(), b.endpoint(), "AAAA", b.authB64(), null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> push.subscribe(ana.id(), b.endpoint(), b.p256dh(), "AAAA", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> subscribe(ana, b, List.of("REPLIES", "NOPE"))).isInstanceOf(ApiException.class);
        // Real push services pass (no request is made at subscribe time).
        props.setAllowInsecureLocalhost(false);
        assertThat(push.subscribe(ana.id(), "https://fcm.googleapis.com/fcm/send/abc" + System.nanoTime(), b.p256dh(), b.authB64(), null, null)
                .categories()).containsExactlyElementsOf(PushService.DEFAULT_CATEGORIES);
        assertThatThrownBy(() -> subscribe(ana, b, null)).isInstanceOf(ApiException.class); // plain-HTTP localhost is test-only
    }

    @Test
    void replyAndMessagePushesAreEncryptedSignedAndCarryOnlyWhatTheNotificationShows() throws Exception {
        AuthUser ana = user("pc"), ben = user("pd");
        Browser benPhone = browser("ben");
        var device = subscribe(ben, benPhone, null);
        assertThat(push.devices(ben.id())).extracting(PushService.Device::id).containsExactly(device.id());

        notifications.commentReply(ben.id(), ana.id(), ana.username(), 42L, 7L, 3L);
        Received r = next();
        JsonNode payload = open(r, benPhone);
        assertThat(payload.get("title").asText()).isEqualTo("Howlers");
        assertThat(payload.get("body").asText()).isEqualTo("u/" + ana.username() + " replied to your comment");
        assertThat(payload.get("url").asText()).isEqualTo("/p/42#c-7");
        assertThat(payload.get("type").asText()).isEqualTo("COMMENT_REPLY");
        assertThat(r.headers().get("topic")).hasSize(32);
        long deadline = System.currentTimeMillis() + 5000; // the sender records success after the response arrives
        while (!jdbc.sql("SELECT last_used_at IS NOT NULL FROM push_subscriptions WHERE id = ?").param(device.id())
                .query(Boolean.class).single()) {
            assertThat(System.currentTimeMillis()).as("last_used_at recorded").isLessThan(deadline);
            Thread.onSpinWait();
        }

        // A second reply in the same thread within the cooldown stays in the inbox only (batching).
        notifications.commentReply(ben.id(), ana.id(), ana.username(), 42L, 8L, 3L);
        assertNothingArrives();

        var convo = dms.start(ana, ben.username());
        dms.send(convo.id(), ana, new SendRequest("my bank PIN is 1234", null));
        JsonNode dm = open(next(), benPhone);
        assertThat(dm.get("title").asText()).isEqualTo("New message");
        assertThat(dm.get("body").asText()).isEqualTo("u/" + ana.username() + " sent you a message");
        assertThat(dm.toString()).doesNotContain("1234").doesNotContain("PIN");
        assertThat(dm.get("url").asText()).isEqualTo("/messages/" + convo.id());

        // The sender never gets a push for their own message.
        assertNothingArrives();
    }

    @Test
    void preferencesMutesAndRateLimitsAreRespected() throws Exception {
        AuthUser ana = user("pe"), ben = user("pf"), cat = user("pg");
        Browser benLaptop = browser("benl");
        var d = subscribe(ben, benLaptop, List.of("MENTIONS"));
        notifications.commentReply(ben.id(), ana.id(), ana.username(), 1L, 2L, 3L); // REPLIES is off on this device
        notifications.newFollower(ben.id(), ana.id(), ana.username());             // FOLLOWERS is off by default
        assertNothingArrives();

        push.updateCategories(ben.id(), d.id(), List.of("MENTIONS", "MESSAGES"), null);
        mutes.muteUser(ben, cat.username(), true);
        notifications.mention(ben.id(), cat.id(), cat.username(), 5L, null); // muted actor: no notification at all
        var convo = dms.start(cat, ben.username());
        dms.send(convo.id(), cat, new SendRequest("hello", null));           // muted sender: message arrives, no push
        assertNothingArrives();

        props.setMaxPerUserPerMinute(2);
        try {
            for (int i = 0; i < 4; i++) notifications.mention(ben.id(), ana.id(), ana.username(), 100L + i, null);
            open(next(), benLaptop);
            open(next(), benLaptop);
            assertNothingArrives(); // the other two stay in the inbox only
        } finally {
            props.setMaxPerUserPerMinute(6);
        }
        assertThatThrownBy(() -> push.updateCategories(ana.id(), d.id(), List.of("MENTIONS"), null)).isInstanceOf(ApiException.class);
    }

    @Test
    void goneSubscriptionsAreCleanedUpAndTestPushesReportTheOutcome() throws Exception {
        AuthUser ana = user("ph"), ben = user("pi");
        Browser phone = browser("phone"), old = browser("old");
        var dPhone = subscribe(ben, phone, null);
        var dOld = subscribe(ben, old, null);

        assertThat(push.sendTest(ben.id(), dPhone.id()).outcome()).isEqualTo(PushService.Outcome.DELIVERED);
        assertThat(open(next(), phone).get("type").asText()).isEqualTo("TEST");
        assertThatThrownBy(() -> push.sendTest(ana.id(), dPhone.id())).isInstanceOf(ApiException.class); // not ana's device

        statusByPath.put(java.net.URI.create(old.endpoint()).getPath(), 410);
        assertThat(push.sendTest(ben.id(), dOld.id()).outcome()).isEqualTo(PushService.Outcome.GONE);
        next();
        assertThat(push.devices(ben.id())).extracting(PushService.Device::id).containsExactly(dPhone.id());

        statusByPath.put(java.net.URI.create(phone.endpoint()).getPath(), 500);
        props.setMaxFailures(2);
        try {
            assertThat(push.sendTest(ben.id(), dPhone.id()).outcome()).isEqualTo(PushService.Outcome.FAILED);
            next();
            assertThat(push.devices(ben.id())).hasSize(1);
            push.sendTest(ben.id(), dPhone.id());
            next();
            assertThat(push.devices(ben.id())).isEmpty(); // repeated failures drop the device
        } finally {
            props.setMaxFailures(10);
        }
    }

    @Test
    void aBrowserBelongsToWhoeverSubscribedLastAndSigningOutRemovesIt() throws Exception {
        AuthUser ana = user("pj"), ben = user("pk"), cat = user("pl");
        Browser shared = browser("shared");
        var first = subscribe(ana, shared, List.of("MENTIONS"));
        var again = subscribe(ana, shared, null); // refresh keeps the device's choices
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.categories()).containsExactly("MENTIONS");

        subscribe(ben, shared, null); // someone else signs in on the same browser and enables push
        assertThat(push.devices(ana.id())).isEmpty();
        assertThat(push.devices(ben.id())).hasSize(1);
        notifications.mention(ana.id(), cat.id(), cat.username(), 9L, null);
        assertNothingArrives(); // ana's mentions no longer go to ben's browser

        push.unsubscribeEndpoint(ana.id(), shared.endpoint()); // not ana's any more: no effect
        assertThat(push.devices(ben.id())).hasSize(1);
        push.unsubscribeEndpoint(ben.id(), shared.endpoint());
        assertThat(push.devices(ben.id())).isEmpty();

        var d = subscribe(cat, browser("cat"), null);
        push.unsubscribe(cat.id(), d.id());
        assertThat(push.devices(cat.id())).isEmpty();
    }
}
