package app.howlers.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WebPushCryptoTest {

    // RFC 8291, Appendix A ("Intermediate Values for Encryption").
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String MESSAGE = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private static byte[] b(String s) {
        return WebPushCrypto.b64(s);
    }

    @Test
    void encryptionMatchesTheRfc8291TestVector() {
        KeyPair as = new KeyPair(WebPushCrypto.publicKey(b(AS_PUBLIC)), WebPushCrypto.privateKey(b(AS_PRIVATE)));
        byte[] out = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), b(UA_PUBLIC), b(AUTH_SECRET), as, b(SALT));
        assertThat(WebPushCrypto.b64(out)).isEqualTo(MESSAGE);
    }

    @Test
    void decryptionOfTheRfc8291TestVectorYieldsThePlaintext() {
        byte[] plain = WebPushCrypto.decrypt(b(MESSAGE), WebPushCrypto.privateKey(b(UA_PRIVATE)), b(UA_PUBLIC), b(AUTH_SECRET));
        assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
    }

    @Test
    void randomisedEncryptionRoundTripsAndNeverRepeats() {
        KeyPair ua = WebPushCrypto.generateKeyPair();
        byte[] uaPub = WebPushCrypto.encode((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] msg = "{\"title\":\"Howlers\",\"body\":\"ünïcode ✓\"}".getBytes(StandardCharsets.UTF_8);
        byte[] a = WebPushCrypto.encrypt(msg, uaPub, auth);
        byte[] c = WebPushCrypto.encrypt(msg, uaPub, auth);
        assertThat(a).isNotEqualTo(c); // fresh salt and ephemeral key every time
        assertThat(WebPushCrypto.decrypt(a, (ECPrivateKey) ua.getPrivate(), uaPub, auth)).isEqualTo(msg);
        assertThat(a.length).isEqualTo(16 + 4 + 1 + 65 + msg.length + 1 + 16);

        byte[] tampered = Arrays.copyOf(a, a.length);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.decrypt(tampered, (ECPrivateKey) ua.getPrivate(), uaPub, auth))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] wrongAuth = auth.clone();
        wrongAuth[0] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.decrypt(a, (ECPrivateKey) ua.getPrivate(), uaPub, wrongAuth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPointsOffTheCurveAndMalformedKeys() {
        byte[] bad = b(UA_PUBLIC);
        bad[64] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.publicKey(bad)).hasMessageContaining("not a point");
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[33])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.privateKey(new byte[32])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[10], b(UA_PUBLIC), new byte[8]))
                .hasMessageContaining("auth secret");
    }

    @Test
    void vapidAuthorizationIsAValidEs256JwtForThePushServiceOrigin() throws Exception {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        String pub = WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) kp.getPublic()));
        String priv = WebPushCrypto.b64(WebPushCrypto.encode((ECPrivateKey) kp.getPrivate()));
        Vapid v = Vapid.of(pub, priv, "mailto:ops@howlers.test");
        assertThat(v.publicKey()).isEqualTo(pub);

        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        String header = v.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc"), now);
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + pub);
        String jwt = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"")
                .contains("\"sub\":\"mailto:ops@howlers.test\"")
                .contains("\"exp\":" + now.plusSeconds(12 * 3600).getEpochSecond());
        assertThat(Vapid.verify((ECPublicKey) kp.getPublic(), parts[0] + "." + parts[1], WebPushCrypto.b64(parts[2]))).isTrue();
        assertThat(WebPushCrypto.b64(parts[2])).hasSize(64); // raw r|s, not DER

        KeyPair other = WebPushCrypto.generateKeyPair();
        String otherPub = WebPushCrypto.b64(WebPushCrypto.encode((ECPublicKey) other.getPublic()));
        assertThatThrownBy(() -> Vapid.of(otherPub, priv, "mailto:ops@howlers.test")).hasMessageContaining("does not belong");
        assertThatThrownBy(() -> Vapid.of(pub, priv, "ops@howlers.test")).hasMessageContaining("VAPID_SUBJECT");
        assertThatThrownBy(() -> Vapid.of("nope", priv, "mailto:a@b.c")).hasMessageNotContaining(priv);
    }
}
