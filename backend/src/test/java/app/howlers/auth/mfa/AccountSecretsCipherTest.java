package app.howlers.auth.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AccountSecretsCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithAFreshNonceAndBindsThePurpose() {
        AccountSecretsCipher c = new AccountSecretsCipher(AccountSecretsCipher.resolveKey(KEY, null, true));
        String a = c.encrypt("JBSWY3DPEHPK3PXP", "totp:1");
        String b = c.encrypt("JBSWY3DPEHPK3PXP", "totp:1");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(c.decrypt(a, "totp:1")).isEqualTo("JBSWY3DPEHPK3PXP");
        // Copied to another user's row: refuses to decrypt.
        assertThatThrownBy(() -> c.decrypt(a, "totp:2")).isInstanceOf(IllegalStateException.class);
        // Another key: refuses too.
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new AccountSecretsCipher(other).decrypt(a, "totp:1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodNeedsARealKeyAndDevDerivesAStableOne() {
        assertThatThrownBy(() -> AccountSecretsCipher.resolveKey("", "jwt", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ACCOUNT_SECRETS_KEY is required");
        assertThatThrownBy(() -> AccountSecretsCipher.resolveKey("c2hvcnQ=", "jwt", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly 32");
        assertThat(AccountSecretsCipher.resolveKey(null, "jwt", false)).isEqualTo(AccountSecretsCipher.resolveKey("", "jwt", false))
                .hasSize(32);
        assertThat(AccountSecretsCipher.resolveKey(null, "jwt-a", false)).isNotEqualTo(AccountSecretsCipher.resolveKey(null, "jwt-b", false));
        assertThat(AccountSecretsCipher.decodeKey(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))).hasSize(32);
        assertThat(AccountSecretsCipher.decodeKey("not base64")).isNull();
    }
}
