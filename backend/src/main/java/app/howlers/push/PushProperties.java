package app.howlers.push;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web push settings ({@code howlers.push.*}, D-040). Push is available only when a VAPID key pair is configured.
 * Mutable on purpose: the integration test points delivery at a local fake push service and sets a throwaway key
 * pair without starting a new application context.
 */
@ConfigurationProperties(prefix = "howlers.push")
public class PushProperties {
    /** base64url, uncompressed P-256 point (65 bytes), as printed by {@code VapidKeyGenerator} or {@code web-push}. */
    private String vapidPublicKey = "";
    /** base64url, raw 32-byte P-256 scalar. Server-side only, never logged and never sent to the browser. */
    private String vapidPrivateKey = "";
    /** {@code mailto:} or {@code https:} contact for push services (RFC 8292). */
    private String vapidSubject = "";
    /** How long a push service keeps an undelivered message (Web Push TTL header). */
    private Duration ttl = Duration.ofHours(24);
    /**
     * Push service hosts the server may POST to (suffix match on a dot boundary). Subscriptions to any other endpoint
     * are refused, so a client can't make the server send requests to arbitrary or internal addresses.
     */
    private List<String> allowedHosts = new ArrayList<>(List.of(
            "fcm.googleapis.com", "push.services.mozilla.com", "notify.windows.com", "push.apple.com"));
    /** Tests only: also accept plain-HTTP endpoints on 127.0.0.1/localhost (the local fake push service). */
    private boolean allowInsecureLocalhost = false;
    /** At most this many pushes per user per minute; the rest stay in the in-app inbox only. */
    private int maxPerUserPerMinute = 6;
    /** A grouped notification (same thread) pushes at most once per this window while it stays unread. */
    private Duration groupCooldown = Duration.ofMinutes(5);
    /** Devices that keep failing (not 404/410, which delete at once) are dropped after this many consecutive failures. */
    private int maxFailures = 10;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getVapidPublicKey() { return vapidPublicKey; }
    public void setVapidPublicKey(String v) { this.vapidPublicKey = v == null ? "" : v.trim(); }
    public String getVapidPrivateKey() { return vapidPrivateKey; }
    public void setVapidPrivateKey(String v) { this.vapidPrivateKey = v == null ? "" : v.trim(); }
    public String getVapidSubject() { return vapidSubject; }
    public void setVapidSubject(String v) { this.vapidSubject = v == null ? "" : v.trim(); }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
    public boolean isAllowInsecureLocalhost() { return allowInsecureLocalhost; }
    public void setAllowInsecureLocalhost(boolean v) { this.allowInsecureLocalhost = v; }
    public int getMaxPerUserPerMinute() { return maxPerUserPerMinute; }
    public void setMaxPerUserPerMinute(int v) { this.maxPerUserPerMinute = v; }
    public Duration getGroupCooldown() { return groupCooldown; }
    public void setGroupCooldown(Duration v) { this.groupCooldown = v; }
    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int v) { this.maxFailures = v; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration v) { this.requestTimeout = v; }
}
