package app.howlers.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All Howlers-specific configuration, bound from {@code howlers.*} (environment variables in production). */
@ConfigurationProperties(prefix = "howlers")
public record AppProperties(Jwt jwt, Cookie cookie, Cors cors, App app, Mail mail, Storage storage,
                            RateLimit rateLimit, Seed seed, Ai ai) {

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl, String issuer) {}

    public record Cookie(boolean secure) {}

    public record Cors(List<String> allowedOrigins) {}

    /** publicUrl = where the frontend lives; used to build links in emails. */
    public record App(String publicUrl, String name) {}

    /**
     * mode: "log" (dev: prints mail to the log), "smtp" (any SMTP relay, including Brevo's) or "brevo" (Brevo's
     * transactional REST API, D-038). The from address must be a sender verified with the provider.
     */
    public record Mail(String mode, String fromAddress, String fromName, String replyTo, Brevo brevo) {}

    /** Brevo REST settings; {@code apiKey} comes from the environment only and is never logged. */
    public record Brevo(String apiKey, String baseUrl, boolean removeTrackingPixel, Duration timeout) {}

    /**
     * driver: "local" (filesystem) or "s3" (any S3-compatible service: AWS S3, MinIO). See D-007 and D-023.
     * {@code s3} may be null when the local driver is used.
     */
    public record Storage(String driver, String localDir, long maxImageBytes, long maxMediaMb, S3 s3) {}

    /**
     * S3-compatible object storage. {@code endpoint} is blank for AWS (region endpoint) or e.g. {@code http://minio:9000};
     * {@code pathStyle} must be true for MinIO. Credentials come from the environment, never from the repo. Blank
     * credentials fall back to the AWS default chain (instance roles, AWS_* env vars).
     */
    public record S3(String bucket, String endpoint, String region, String accessKey, String secretKey, boolean pathStyle,
                     long multipartThresholdMb, boolean createBucket) {}

    public record RateLimit(boolean enabled) {}

    public record Seed(boolean enabled) {}

    /**
     * provider: "auto" (the hosted model when an API key and model are set, otherwise offline), "hosted", or "offline".
     * The key is read from the server environment only and never sent to the browser.
     */
    public record Ai(String provider, String apiKey, String model, String effort, long maxTokens) {}
}
