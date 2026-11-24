package app.howlers.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A software WebAuthn authenticator for tests: an EC P-256 key pair, "none" attestation, a signature counter, and
 * just enough CBOR to build attestation objects and COSE keys. It produces the same JSON a browser's
 * {@code PublicKeyCredential.toJSON()} would, so the server code under test is exactly what browsers hit.
 */
public final class SoftwareAuthenticator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keys;
    private final byte[] credentialId = new byte[32];
    private byte[] userHandle;
    private long counter;
    /** User verification (PIN/biometric) flag; the server requires it. */
    public boolean userVerified = true;
    /** Counter increment per assertion; 0 simulates an authenticator without a counter. */
    public int counterStep = 1;

    public SoftwareAuthenticator() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            keys = g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        RANDOM.nextBytes(credentialId);
        counter = 5;
    }

    public long counter() { return counter; }

    public void setCounter(long c) { counter = c; }

    public String credentialIdB64() { return B64.encodeToString(credentialId); }

    /** navigator.credentials.create(): takes the server's {@code publicKey} options, returns the credential JSON. */
    public ObjectNode register(JsonNode publicKey, String origin) throws Exception {
        String rpId = publicKey.path("rp").path("id").asText();
        userHandle = Base64.getUrlDecoder().decode(publicKey.path("user").path("id").asText());
        byte[] clientData = clientData("webauthn.create", publicKey.path("challenge").asText(), origin);

        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(flags(true));
        authData.write(ByteBuffer.allocate(4).putInt((int) counter).array());
        authData.write(new byte[16]); // AAGUID: none
        authData.write(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        authData.write(credentialId);
        authData.write(coseKey());

        Map<Object, Object> att = new LinkedHashMap<>();
        att.put("fmt", "none");
        att.put("attStmt", new LinkedHashMap<>());
        att.put("authData", authData.toByteArray());

        ObjectNode resp = JSON.createObjectNode();
        resp.put("clientDataJSON", B64.encodeToString(clientData));
        resp.put("attestationObject", B64.encodeToString(Cbor.encode(att)));
        resp.putArray("transports").add("internal");
        return credential(resp);
    }

    /** navigator.credentials.get(): signs the challenge with the registered key. */
    public ObjectNode authenticate(JsonNode publicKey, String origin) throws Exception {
        String rpId = publicKey.path("rpId").asText();
        counter += counterStep;
        byte[] clientData = clientData("webauthn.get", publicKey.path("challenge").asText(), origin);
        ByteArrayOutputStream authData = new ByteArrayOutputStream();
        authData.write(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.write(flags(false));
        authData.write(ByteBuffer.allocate(4).putInt((int) counter).array());
        byte[] ad = authData.toByteArray();

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keys.getPrivate());
        sig.update(ad);
        sig.update(sha256(clientData));

        ObjectNode resp = JSON.createObjectNode();
        resp.put("clientDataJSON", B64.encodeToString(clientData));
        resp.put("authenticatorData", B64.encodeToString(ad));
        resp.put("signature", B64.encodeToString(sig.sign()));
        resp.put("userHandle", B64.encodeToString(userHandle));
        return credential(resp);
    }

    private ObjectNode credential(ObjectNode response) {
        ObjectNode c = JSON.createObjectNode();
        c.put("type", "public-key");
        c.put("id", credentialIdB64());
        c.put("rawId", credentialIdB64());
        c.set("response", response);
        c.putObject("clientExtensionResults");
        c.put("authenticatorAttachment", "platform");
        return c;
    }

    private int flags(boolean attested) {
        int f = 0x01; // user present
        if (userVerified) f |= 0x04;
        if (attested) f |= 0x40;
        return f;
    }

    private static byte[] clientData(String type, String challenge, String origin) throws Exception {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", type);
        n.put("challenge", challenge);
        n.put("origin", origin);
        n.put("crossOrigin", false);
        return JSON.writeValueAsBytes(n);
    }

    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1, 2);    // kty: EC2
        m.put(3, -7);   // alg: ES256
        m.put(-1, 1);   // crv: P-256
        m.put(-2, unsigned32(pub.getW().getAffineX()));
        m.put(-3, unsigned32(pub.getW().getAffineY()));
        return Cbor.encode(m);
    }

    private static byte[] unsigned32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    /** Minimal CBOR writer: unsigned/negative integers, byte strings, text strings and maps. */
    static final class Cbor {
        static byte[] encode(Object o) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, o);
            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream out, Object o) {
            if (o instanceof Integer i) {
                if (i >= 0) head(out, 0, i);
                else head(out, 1, -1L - i);
            } else if (o instanceof byte[] b) {
                head(out, 2, b.length);
                out.writeBytes(b);
            } else if (o instanceof String s) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                head(out, 3, b.length);
                out.writeBytes(b);
            } else if (o instanceof Map<?, ?> m) {
                head(out, 5, m.size());
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    write(out, e.getKey());
                    write(out, e.getValue());
                }
            } else {
                throw new IllegalArgumentException("unsupported " + o);
            }
        }

        private static void head(ByteArrayOutputStream out, int major, long len) {
            int m = major << 5;
            if (len < 24) out.write(m | (int) len);
            else if (len < 0x100) { out.write(m | 24); out.write((int) len); }
            else if (len < 0x10000) { out.write(m | 25); out.write((int) (len >> 8)); out.write((int) len & 0xff); }
            else { out.write(m | 26); out.writeBytes(ByteBuffer.allocate(4).putInt((int) len).array()); }
        }
    }
}
