package app.howlers.common.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the {@code prod} profile with development defaults (D-026): the dev JWT secret, the dev funding
 * webhook secret, a default or empty database password, no visitor salt, no account secrets key (D-042), insecure
 * cookies, demo MinIO credentials,
 * or a Prometheus endpoint exposed on the public port. Every problem is listed at once, so a deploy needs one fix-up.
 */
@Component
@Profile("prod")
public class ProductionSafetyCheck implements InitializingBean {
    static final String DEV_JWT_SECRET = "ZGV2LW9ubHktc2VjcmV0LWNoYW5nZS1tZS1iZWZvcmUtZGVwbG95aW5nLXN0cmF0YQ==";
    static final String DEV_WEBHOOK_SECRET = "dev-only-demo-webhook-secret-change-me";
    private static final Set<String> WEAK_DB_PASSWORDS = Set.of("", "howlers", "postgres", "password", "change-me-to-a-long-random-string");
    private static final Set<String> DEMO_S3_SECRETS = Set.of("minioadmin", "minio123", "change-me");

    private final Environment env;

    public ProductionSafetyCheck(Environment env) {
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start the prod profile with unsafe settings:\n - "
                    + String.join("\n - ", problems) + "\nSee docs/DEPLOYMENT.md (environment reference).");
        }
    }

    static List<String> problems(Environment env) {
        List<String> out = new ArrayList<>();
        String jwt = env.getProperty("howlers.jwt.secret", "");
        if (jwt.isBlank() || DEV_JWT_SECRET.equals(jwt)) {
            out.add("JWT_SECRET is unset or the development default (generate one: openssl rand -base64 48)");
        } else {
            try {
                if (Base64.getDecoder().decode(jwt).length < 32) out.add("JWT_SECRET must be base64 of at least 32 random bytes");
            } catch (IllegalArgumentException e) {
                out.add("JWT_SECRET must be base64 (openssl rand -base64 48)");
            }
        }
        boolean stripe = "stripe".equalsIgnoreCase(env.getProperty("howlers.funding.provider", "demo").strip());
        if (env.getProperty("howlers.funding.enabled", Boolean.class, true) && stripe) {
            // Stripe (D-034): renewals, refunds and cancellations arrive only by signed webhook.
            if (env.getProperty("howlers.funding.stripe.webhook-secret", "").isBlank()) {
                out.add("STRIPE_WEBHOOK_SECRET is unset (FUNDING_PROVIDER=stripe needs the endpoint's signing secret, whsec_...)");
            }
        } else if (env.getProperty("howlers.funding.enabled", Boolean.class, true)) {
            String hook = env.getProperty("howlers.funding.demo-webhook-secret", "");
            if (hook.isBlank() || DEV_WEBHOOK_SECRET.equals(hook) || hook.length() < 16) {
                out.add("FUNDING_DEMO_WEBHOOK_SECRET is unset, shorter than 16 characters or the development default");
            }
        }
        String db = env.getProperty("spring.datasource.password", "");
        if (WEAK_DB_PASSWORDS.contains(db.toLowerCase(Locale.ROOT)) || db.length() < 12) {
            out.add("DATABASE_PASSWORD is empty, a default or shorter than 12 characters");
        }
        if (env.getProperty("howlers.files.visitor-salt", "").isBlank()) {
            out.add("FILE_VISITOR_SALT is unset (share-link visitor hashes need a stable server secret)");
        }
        String accountKey = env.getProperty("howlers.account-security.secrets-key", "");
        if (accountKey.isBlank()) {
            out.add("ACCOUNT_SECRETS_KEY is unset (encrypts two-factor secrets; generate one: openssl rand -base64 32)");
        } else if (app.howlers.auth.mfa.AccountSecretsCipher.decodeKey(accountKey) == null) {
            out.add("ACCOUNT_SECRETS_KEY must be base64 of exactly 32 random bytes (openssl rand -base64 32)");
        }
        if (!env.getProperty("howlers.cookie.secure", Boolean.class, false)) {
            out.add("howlers.cookie.secure must be true behind HTTPS (COOKIE_SECURE=true)");
        }
        if ("s3".equalsIgnoreCase(env.getProperty("howlers.storage.driver", "local"))) {
            String secret = env.getProperty("howlers.storage.s3.secret-key", "");
            if (DEMO_S3_SECRETS.contains(secret.toLowerCase(Locale.ROOT))) out.add("S3_SECRET_KEY is a well-known default");
        }
        String exposure = env.getProperty("management.endpoints.web.exposure.include", "");
        if (exposure.contains("prometheus") || exposure.contains("*")) {
            String mgmt = env.getProperty("management.server.port", "");
            String server = env.getProperty("server.port", "8080");
            if (mgmt.isBlank() || mgmt.equals(server)) {
                out.add("Metrics are exposed on the public port: set MANAGEMENT_PORT to a separate, unpublished port");
            }
        }
        return out;
    }
}
