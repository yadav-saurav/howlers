package app.howlers.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class TotpTest {
    private static final byte[] SEED_SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA256 = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA512 =
            "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238, Appendix B: every test vector, 8 digits, 30-second steps. */
    @ParameterizedTest
    @CsvSource({
            "59,          94287082, 46119246, 90693936",
            "1111111109,  07081804, 68084774, 25091201",
            "1111111111,  14050471, 67062674, 99943326",
            "1234567890,  89005924, 91819424, 93441116",
            "2000000000,  69279037, 90698825, 38618901",
            "20000000000, 65353130, 77737706, 47863826"})
    void rfc6238TestVectors(long time, String sha1, String sha256, String sha512) {
        long step = Totp.stepAt(time);
        assertThat(Totp.code(SEED_SHA1, step, 8, "HmacSHA1")).isEqualTo(sha1);
        assertThat(Totp.code(SEED_SHA256, step, 8, "HmacSHA256")).isEqualTo(sha256);
        assertThat(Totp.code(SEED_SHA512, step, 8, "HmacSHA512")).isEqualTo(sha512);
    }

    @Test
    void sixDigitCodesAreTheLowDigitsOfTheSameValue() {
        String secret = Totp.base32Encode(SEED_SHA1);
        assertThat(Totp.code(secret, Totp.stepAt(59))).isEqualTo("287082");
        assertThat(Totp.code(secret, Totp.stepAt(1111111109))).isEqualTo("081804");
    }

    @Test
    void base32MatchesRfc4648() {
        assertThat(Totp.base32Encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32Encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(new String(Totp.base32Decode("MZXW6YTBOI======"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(new String(Totp.base32Decode("mzxw 6ytb oi"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(Totp.base32Encode(SEED_SHA1)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThatThrownBy(() -> Totp.base32Decode("not!base32")).isInstanceOf(IllegalArgumentException.class);
        String s = Totp.newSecret();
        assertThat(s).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.base32Decode(s)).hasSize(20);
    }

    @Test
    void oneStepOfClockSkewEitherWayIsAccepted() {
        String secret = Totp.newSecret();
        long now = 1_800_000_015L;
        long step = Totp.stepAt(now);
        assertThat(Totp.verify(secret, Totp.code(secret, step), now, null)).hasValue(step);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, step + 2), now, null)).isEmpty();
    }

    @Test
    void aCodeCantBeReusedNorAnOlderOneUsedAfterIt() {
        String secret = Totp.newSecret();
        long now = 1_800_000_015L;
        long step = Totp.stepAt(now);
        String code = Totp.code(secret, step);
        assertThat(Totp.verify(secret, code, now, step)).isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, step)).isEmpty();
        assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now, step)).hasValue(step + 1);
    }

    @Test
    void malformedCodesAreRejected() {
        String secret = Totp.newSecret();
        long now = 1_800_000_015L;
        String code = Totp.code(secret, Totp.stepAt(now));
        assertThat(Totp.verify(secret, code.substring(0, 3) + " " + code.substring(3), now, null)).isPresent();
        assertThat(Totp.verify(secret, null, now, null)).isEmpty();
        assertThat(Totp.verify(secret, "12345", now, null)).isEmpty();
        assertThat(Totp.verify(secret, "abcdef", now, null)).isEmpty();
        assertThat(Totp.verify(secret, code + "0", now, null)).isEmpty();
    }

    @Test
    void otpauthUriHasTheStandardParameters() {
        String uri = Totp.otpauthUri("Howlers", "mira k", "JBSWY3DPEHPK3PXP");
        assertThat(uri).isEqualTo("otpauth://totp/Howlers:mira%20k?secret=JBSWY3DPEHPK3PXP&issuer=Howlers&algorithm=SHA1&digits=6&period=30");
        assertThat(QrSvg.render(uri)).startsWith("<svg").contains("viewBox=\"0 0 ").endsWith("</svg>").doesNotContain("JBSWY3DPEHPK3PXP");
    }

    @Test
    void recoveryCodesAvoidLookAlikesAndAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String c = TwoFactorService.newRecoveryCode();
            assertThat(c).matches("[a-hjkmnp-z2-9]{5}-[a-hjkmnp-z2-9]{5}");
            seen.add(c);
        }
        assertThat(seen).hasSize(200);
        assertThat(TwoFactorService.recoveryHash(1, "abcdeabcde")).isNotEqualTo(TwoFactorService.recoveryHash(2, "abcdeabcde"));
    }
}
