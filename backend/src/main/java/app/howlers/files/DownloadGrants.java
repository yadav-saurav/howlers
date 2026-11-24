package app.howlers.files;

import app.howlers.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Short-lived, signed download URLs. Browsers can't attach a Bearer token to a plain navigation, so after the API
 * checks ownership or a share link's password it hands out {@code /api/v1/downloads/{grant}}, valid for a few minutes.
 * A grant is "{kind}.{targetId}.{viewerId}.{expiresEpochSecond}" plus an HMAC-SHA256 over it, keyed from the server's
 * JWT secret with a purpose label. Grants are not secret-bearing beyond their short lifetime, and every download
 * re-checks the link's state (revoked, expired, limit) at use time.
 */
@Component
public class DownloadGrants {
    static final Duration TTL = Duration.ofMinutes(5);

    /** O = owner download of a file, L = download through a share link, D = a direct-message image (D-024). */
    public enum Kind { O, L, D }

    public record Grant(Kind kind, long targetId, Long viewerId, Instant expiresAt) {}

    private final byte[] key;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DownloadGrants(AppProperties props) {
        this(props.jwt().secret(), Clock.systemUTC());
    }

    DownloadGrants(String secret, Clock clock) {
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(("howlers-download-grant:" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        this.clock = clock;
    }

    public Instant expiry() {
        return clock.instant().plus(TTL);
    }

    /**
     * An expiry at least {@code minTtl} away, rounded up to a multiple of {@code minTtl}. Grants issued within the same
     * window are identical strings, so the browser cache can reuse an image across message refetches.
     */
    public Instant bucketedExpiry(Duration minTtl) {
        long step = Math.max(60, minTtl.toSeconds());
        long min = clock.instant().getEpochSecond() + step;
        return Instant.ofEpochSecond(((min + step - 1) / step) * step);
    }

    public Instant now() {
        return clock.instant();
    }

    public String issue(Kind kind, long targetId, Long viewerId, Instant expiresAt) {
        String payload = kind + "." + targetId + "." + (viewerId == null ? 0 : viewerId) + "." + expiresAt.getEpochSecond();
        String body = b64(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + b64(sign(body));
    }

    public Optional<Grant> verify(String grant) {
        if (grant == null || grant.length() > 300) return Optional.empty();
        int dot = grant.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String body = grant.substring(0, dot);
        byte[] sig;
        try {
            sig = Base64.getUrlDecoder().decode(grant.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(sig, sign(body))) return Optional.empty();
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\.");
            if (p.length != 4) return Optional.empty();
            Instant exp = Instant.ofEpochSecond(Long.parseLong(p[3]));
            if (!exp.isAfter(clock.instant())) return Optional.empty();
            long viewer = Long.parseLong(p[2]);
            return Optional.of(new Grant(Kind.valueOf(p[0]), Long.parseLong(p[1]), viewer == 0 ? null : viewer, exp));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
