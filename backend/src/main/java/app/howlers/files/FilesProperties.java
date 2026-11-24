package app.howlers.files;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Private file settings, bound from {@code howlers.files.*}.
 *
 * @param maxFileMb   largest single upload (must stay below spring.servlet.multipart.max-file-size)
 * @param quotaMb     storage quota per user (live files only; deleted files don't count)
 * @param visitorSalt server-side secret for hashing visitor IPs in share access logs. When blank, a random salt is
 *                    generated at startup, so hashes can't be correlated across restarts either.
 */
@ConfigurationProperties(prefix = "howlers.files")
public record FilesProperties(long maxFileMb, long quotaMb, String visitorSalt) {
    public FilesProperties {
        if (maxFileMb <= 0) maxFileMb = 500;
        if (quotaMb <= 0) quotaMb = 1024;
    }

    public long maxFileBytes() {
        return maxFileMb * 1024 * 1024;
    }

    public long quotaBytes() {
        return quotaMb * 1024 * 1024;
    }
}
