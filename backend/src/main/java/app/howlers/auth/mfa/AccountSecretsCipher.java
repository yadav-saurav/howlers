package app.howlers.auth.mfa;

import app.howlers.auth.AccountSecurityProperties;
import app.howlers.common.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Encrypts account secrets (TOTP seeds) at rest with AES-256-GCM (D-042). The key is {@code ACCOUNT_SECRETS_KEY}
 * (base64 of 32 bytes). Each value gets a fresh 96-bit nonce, and the "purpose" (e.g. {@code totp:42}) is bound as
 * associated data, so a ciphertext copied to another user's row fails to decrypt. Stored form: {@code v1:<base64>}.
 *
 * <p>In development a missing key is derived from the JWT secret (stable across restarts) with a warning; the prod
 * profile refuses to start without a real key (here and in {@code ProductionSafetyCheck}).
 */
@Component
public class AccountSecretsCipher {
    private static final Logger log = LoggerFactory.getLogger(AccountSecretsCipher.class);
    private static final String PREFIX = "v1:";
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final byte[] macKey;

    @Autowired
    public AccountSecretsCipher(AccountSecurityProperties props, AppProperties app, Environment env) {
        this(resolveKey(props.secretsKey(), app.jwt().secret(), env.acceptsProfiles(Profiles.of("prod"))));
    }

    AccountSecretsCipher(byte[] raw) {
        this.key = new SecretKeySpec(raw, "AES");
        this.macKey = sha256(concat("howlers-account-mac-v1|".getBytes(StandardCharsets.UTF_8), raw));
    }

    static byte[] resolveKey(String configured, String jwtSecret, boolean prod) {
        if (configured != null && !configured.isBlank()) {
            byte[] k = decodeKey(configured);
            if (k == null) throw new IllegalStateException("ACCOUNT_SECRETS_KEY must be base64 of exactly 32 random bytes (openssl rand -base64 32)");
            return k;
        }
        if (prod) throw new IllegalStateException("ACCOUNT_SECRETS_KEY is required in production (openssl rand -base64 32)");
        log.warn("ACCOUNT_SECRETS_KEY is not set; using a key derived from the JWT secret. Fine for development only.");
        return sha256(("howlers-account-secrets-v1|" + (jwtSecret == null ? "" : jwtSecret)).getBytes(StandardCharsets.UTF_8));
    }

    /** The decoded key, or null when it isn't base64 (standard or URL-safe) of exactly 32 bytes. */
    public static byte[] decodeKey(String value) {
        String v = value.strip();
        for (Base64.Decoder d : new Base64.Decoder[]{Base64.getDecoder(), Base64.getUrlDecoder()}) {
            try {
                byte[] k = d.decode(v);
                if (k.length == 32) return k;
            } catch (IllegalArgumentException ignored) {
                // try the other alphabet
            }
        }
        return null;
    }

    public String encrypt(String plaintext, String purpose) {
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String stored, String purpose) {
        if (stored == null || !stored.startsWith(PREFIX)) throw new IllegalStateException("unknown secret format");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE));
            c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key (ACCOUNT_SECRETS_KEY changed) or tampered row.
            throw new IllegalStateException("could not decrypt an account secret (was ACCOUNT_SECRETS_KEY changed?)", e);
        }
    }

    /** Truncated keyed hash for coarse, non-reversible identifiers (device fingerprints, network prefixes). */
    public String keyedHash(String value, int hexChars) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, Math.min(hexChars, hex.length()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }
}
