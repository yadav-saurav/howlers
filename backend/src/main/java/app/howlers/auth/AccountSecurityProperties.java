package app.howlers.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stronger accounts (D-041, D-042), bound from {@code howlers.account-security.*}.
 *
 * @param secretsKey      base64 of 32 random bytes ({@code ACCOUNT_SECRETS_KEY}); encrypts TOTP secrets at rest.
 *                        Required in prod. Blank in development: a stable key is derived from the JWT secret.
 * @param webauthn        relying party settings for passkeys
 * @param mfa             second-step token lifetime and the lockout after repeated wrong codes
 * @param newSigninAlerts global switch for the "new sign-in" email (users can also turn it off for themselves)
 */
@ConfigurationProperties(prefix = "howlers.account-security")
public record AccountSecurityProperties(String secretsKey, Webauthn webauthn, Mfa mfa, Boolean newSigninAlerts) {

    public AccountSecurityProperties {
        if (webauthn == null) webauthn = new Webauthn(null, null, null);
        if (mfa == null) mfa = new Mfa(null, null, null);
        if (newSigninAlerts == null) newSigninAlerts = true;
    }

    /**
     * @param rpId    relying party id, a registrable domain (e.g. {@code howlers.app}). Blank = the host of
     *                {@code APP_PUBLIC_URL}. Passkeys are bound to it: changing it orphans every registered passkey.
     * @param rpName  shown by the browser and the authenticator
     * @param origins comma-separated exact origins allowed to run ceremonies. Blank = the origin of {@code APP_PUBLIC_URL}.
     */
    public record Webauthn(String rpId, String rpName, String origins) {
        public String resolvedRpId(String publicUrl) {
            if (rpId != null && !rpId.isBlank()) return rpId.trim().toLowerCase(Locale.ROOT);
            return URI.create(publicUrl.trim()).getHost().toLowerCase(Locale.ROOT);
        }

        public String resolvedRpName() {
            return rpName == null || rpName.isBlank() ? "Howlers" : rpName.trim();
        }

        public Set<String> resolvedOrigins(String publicUrl) {
            Set<String> out = new LinkedHashSet<>();
            if (origins != null && !origins.isBlank()) {
                Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Webauthn::origin).forEach(out::add);
            } else {
                out.add(origin(publicUrl));
            }
            return out;
        }

        /** scheme://host[:port], no path or trailing slash, as browsers send it in clientDataJSON. */
        static String origin(String url) {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        }
    }

    /**
     * @param loginTokenTtl how long the password-accepted, code-pending token lives
     * @param maxAttempts   wrong codes in a row before the account's second step locks
     * @param lockout       how long it stays locked
     */
    public record Mfa(Duration loginTokenTtl, Integer maxAttempts, Duration lockout) {
        public Mfa {
            if (loginTokenTtl == null) loginTokenTtl = Duration.ofMinutes(5);
            if (maxAttempts == null) maxAttempts = 5;
            if (lockout == null) lockout = Duration.ofMinutes(5);
        }
    }
}
