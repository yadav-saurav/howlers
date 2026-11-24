package app.howlers.auth.mfa;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords (D-042). Authenticator apps use HMAC-SHA1, 6 digits and 30-second steps,
 * which is what {@link #verify} checks; {@link #code} also takes the other RFC algorithms so the RFC's test vectors
 * can be checked directly. Secrets are 20 random bytes (160 bits, the RFC 4226 recommendation), shown as base32.
 */
public final class Totp {
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Codes one step either side of now are accepted, for clocks that drift a little. */
    public static final int SKEW_STEPS = 1;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32Encode(b);
    }

    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** HOTP(K, T) truncated to {@code digits}; {@code algorithm} is HmacSHA1, HmacSHA256 or HmacSHA512. */
    public static String code(byte[] key, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            return String.format(Locale.ROOT, "%0" + digits + "d", bin % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(String base32Secret, long step) {
        return code(base32Decode(base32Secret), step, DIGITS, "HmacSHA1");
    }

    /**
     * The step whose code matches, searching now ± {@link #SKEW_STEPS}, skipping steps at or before {@code lastUsedStep}
     * (so a code can't be replayed, nor an older one used after a newer one). Comparison is constant-time.
     */
    public static OptionalLong verify(String base32Secret, String code, long epochSeconds, Long lastUsedStep) {
        if (code == null) return OptionalLong.empty();
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{" + DIGITS + "}")) return OptionalLong.empty();
        byte[] key = base32Decode(base32Secret);
        long now = stepAt(epochSeconds);
        OptionalLong match = OptionalLong.empty();
        for (long s = now - SKEW_STEPS; s <= now + SKEW_STEPS; s++) {
            boolean eq = MessageDigest.isEqual(code(key, s, DIGITS, "HmacSHA1").getBytes(StandardCharsets.US_ASCII),
                    c.getBytes(StandardCharsets.US_ASCII));
            if (eq && (lastUsedStep == null || s > lastUsedStep) && match.isEmpty()) match = OptionalLong.of(s);
        }
        return match;
    }

    /** otpauth://totp/Issuer:account?secret=...&issuer=Issuer&algorithm=SHA1&digits=6&period=30 */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch == '=' || ch == ' ' || ch == '-') continue;
            int v = B32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
