package app.howlers.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push message encryption (RFC 8291, "aes128gcm" content coding from RFC 8188) on the JDK alone: P-256 ECDH,
 * HKDF-SHA-256 and AES-128-GCM are all in the standard providers, so no extra crypto library is needed (D-040).
 *
 * <p>A message is a single record: {@code salt(16) | rs(4) | idlen(1) | keyid(65 = the sender's ephemeral public key) |
 * AES-GCM(plaintext | 0x02)}. {@link #decrypt} is the user agent's side; it exists for the tests and the local fake
 * push service that checks the whole delivery path.
 */
public final class WebPushCrypto {
    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64_OUT = Base64.getUrlEncoder().withoutPadding();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {}

    // ------------------------------------------------------------------ keys

    static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this JDK", e);
        }
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decodes base64url, with or without padding (browsers and libraries differ). */
    public static byte[] b64(String s) {
        return B64.decode(s.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64(byte[] b) {
        return B64_OUT.encodeToString(b);
    }

    /** An uncompressed P-256 point (0x04 | X | Y, 65 bytes), checked to lie on the curve. */
    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Expected an uncompressed P-256 public key (65 bytes)");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        if (!onCurve(x, y)) throw new IllegalArgumentException("The public key is not a point on P-256");
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 public key", e);
        }
    }

    /** A raw P-256 private scalar (32 bytes). */
    public static ECPrivateKey privateKey(byte[] d) {
        if (d.length != 32) throw new IllegalArgumentException("Expected a 32-byte P-256 private key");
        BigInteger s = new BigInteger(1, d);
        if (s.signum() == 0 || s.compareTo(P256.getOrder()) >= 0) throw new IllegalArgumentException("Invalid P-256 private key");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 private key", e);
        }
    }

    public static byte[] encode(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        unsigned(key.getW().getAffineX(), out, 1);
        unsigned(key.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] encode(ECPrivateKey key) {
        byte[] out = new byte[32];
        unsigned(key.getS(), out, 0);
        return out;
    }

    private static void unsigned(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int start = b.length > 32 ? b.length - 32 : 0;
        int len = b.length - start;
        System.arraycopy(b, start, out, offset + 32 - len, len);
    }

    private static boolean onCurve(BigInteger x, BigInteger y) {
        EllipticCurve c = P256.getCurve();
        BigInteger p = ((java.security.spec.ECFieldFp) c.getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger lhs = y.modPow(BigInteger.TWO, p);
        BigInteger rhs = x.modPow(BigInteger.valueOf(3), p).add(c.getA().multiply(x)).add(c.getB()).mod(p);
        return lhs.equals(rhs);
    }

    // ------------------------------------------------------------------ RFC 8291

    /** Encrypts {@code plaintext} for a subscription ({@code p256dh} and {@code auth} as the browser reported them). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    /** Deterministic variant (fixed ephemeral key and salt) so the RFC 8291 test vector can be checked. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair ephemeral, byte[] salt) {
        if (authSecret.length != 16) throw new IllegalArgumentException("The auth secret must be 16 bytes");
        if (plaintext.length + 1 + 16 > RECORD_SIZE) throw new IllegalArgumentException("Payload too large for one record");
        byte[] asPublic = encode((ECPublicKey) ephemeral.getPublic());
        byte[] ecdh = ecdh((ECPrivateKey) ephemeral.getPrivate(), publicKey(uaPublic));
        Keys k = derive(ecdh, authSecret, uaPublic, asPublic, salt);
        byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
        padded[plaintext.length] = 0x02; // last-record delimiter, no padding
        byte[] ciphertext = aesGcm(Cipher.ENCRYPT_MODE, k.cek, k.nonce, padded);
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length)
                .put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext).array();
    }

    /** The user agent's side: decrypts an aes128gcm body with the subscription's private key and auth secret. */
    public static byte[] decrypt(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic, byte[] authSecret) {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int rs = in.getInt();
        int idlen = Byte.toUnsignedInt(in.get());
        byte[] asPublic = new byte[idlen];
        in.get(asPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        if (rs < 18 || ciphertext.length > rs) throw new IllegalArgumentException("Unsupported record layout");
        byte[] ecdh = ecdh(uaPrivate, publicKey(asPublic));
        Keys k = derive(ecdh, authSecret, uaPublic, asPublic, salt);
        byte[] padded = aesGcm(Cipher.DECRYPT_MODE, k.cek, k.nonce, ciphertext);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        if (end < 0 || padded[end] != 0x02) throw new IllegalArgumentException("Missing last-record delimiter");
        return Arrays.copyOf(padded, end);
    }

    private record Keys(byte[] cek, byte[] nonce) {}

    private static Keys derive(byte[] ecdh, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt) {
        // IKM = HKDF(salt = auth_secret, ikm = ecdh_secret, info = "WebPush: info" 0x00 ua_public as_public, L = 32)
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = expand(hmac(authSecret, ecdh), keyInfo, 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        return new Keys(cek, nonce);
    }

    /** HKDF-Expand for L <= 32 (one HMAC block): T(1) = HMAC(PRK, info | 0x01). */
    private static byte[] expand(byte[] prk, byte[] info, int length) {
        return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ecdh(ECPrivateKey priv, ECPublicKey pub) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("ECDH failed", e);
        }
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES-GCM failed", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        ByteBuffer b = ByteBuffer.allocate(n);
        for (byte[] p : parts) b.put(p);
        return b.array();
    }
}
