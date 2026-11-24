package app.howlers.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;

/**
 * VAPID (RFC 8292): the application server proves its identity to push services with a short-lived ES256 JWT whose
 * audience is the push service's origin. The public key is the {@code applicationServerKey} browsers subscribe with.
 */
public final class Vapid {
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyB64;
    private final String subject;

    private Vapid(ECPublicKey publicKey, ECPrivateKey privateKey, String subject) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.publicKeyB64 = WebPushCrypto.b64(WebPushCrypto.encode(publicKey));
        this.subject = subject;
    }

    /**
     * Parses and checks a configured key pair. Throws {@link IllegalArgumentException} with a message that never
     * contains key material when the keys are malformed or don't belong together.
     */
    public static Vapid of(String publicKeyB64, String privateKeyB64, String subject) {
        if (subject == null || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            throw new IllegalArgumentException("VAPID_SUBJECT must be a mailto: or https: URL");
        }
        ECPublicKey pub;
        ECPrivateKey priv;
        try {
            pub = WebPushCrypto.publicKey(WebPushCrypto.b64(publicKeyB64));
            priv = WebPushCrypto.privateKey(WebPushCrypto.b64(privateKeyB64));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("VAPID keys are not a base64url P-256 key pair (" + e.getMessage() + ")");
        }
        Vapid v = new Vapid(pub, priv, subject);
        if (!v.verify("howlers-vapid-self-check", v.sign("howlers-vapid-self-check"))) {
            throw new IllegalArgumentException("VAPID_PUBLIC_KEY does not belong to VAPID_PRIVATE_KEY");
        }
        return v;
    }

    public String publicKey() {
        return publicKeyB64;
    }

    /** {@code Authorization} header value for a request to {@code endpoint}. */
    public String authorization(URI endpoint, Instant now) {
        String aud = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        long exp = now.plus(Duration.ofHours(12)).getEpochSecond(); // push services reject more than 24 h
        String header = WebPushCrypto.b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = WebPushCrypto.b64(("{\"aud\":\"" + json(aud) + "\",\"exp\":" + exp + ",\"sub\":\"" + json(subject) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + claims;
        return "vapid t=" + unsigned + "." + WebPushCrypto.b64(sign(unsigned)) + ", k=" + publicKeyB64;
    }

    private byte[] sign(String data) {
        try {
            // P1363 = the raw 64-byte r|s form JWS needs (not DER).
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(privateKey);
            s.update(data.getBytes(StandardCharsets.US_ASCII));
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean verify(String data, byte[] sig) {
        return verify(publicKey, data, sig);
    }

    static boolean verify(ECPublicKey key, String data, byte[] sig) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(key);
            s.update(data.getBytes(StandardCharsets.US_ASCII));
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
