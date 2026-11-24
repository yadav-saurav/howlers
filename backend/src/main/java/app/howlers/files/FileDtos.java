package app.howlers.files;

import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class FileDtos {
    private FileDtos() {}

    /**
     * scanStatus: CLEAN (accepted), UNSCANNED (E2EE). scanEngine (D-029): STRUCTURAL = type and structure checks only,
     * not an antivirus verdict; CLAMAV = also scanned by ClamAV, which found nothing.
     */
    public record FileItem(long id, String name, String contentType, long sizeBytes, boolean e2ee, String scanStatus,
                           String scanDetail, String scanEngine, String sha256, int activeLinks, Instant createdAt, Instant updatedAt) {}

    public record Usage(long usedBytes, long quotaBytes, long maxFileBytes, long fileCount) {}

    public record RenameRequest(@NotBlank @Size(max = 255) String name) {}

    /** PBKDF2-SHA256 parameters and the AES-GCM-wrapped file key (all base64url). Produced and consumed in the browser. */
    public record WrappedKey(@NotBlank @Size(max = 200) String wrappedKey, @NotBlank @Size(max = 100) String salt,
                             @Min(600_000) @Max(10_000_000) int iterations) {}

    /**
     * keyMode is required for E2EE files and forbidden otherwise. PASSPHRASE requires wrappedKey.
     * password is a server-checked access password (any file); it is unrelated to the E2EE passphrase.
     */
    public record CreateLinkRequest(Instant expiresAt, @Size(min = 4, max = 128) String password,
                                    @Min(1) @Max(10_000) Integer maxDownloads, String keyMode, @Valid WrappedKey wrappedKey) {}

    /** status: ACTIVE, EXPIRED, REVOKED or EXHAUSTED (download limit reached). */
    public record ShareLink(long id, long fileId, String tokenHint, String status, Instant createdAt, Instant expiresAt,
                            boolean hasPassword, Integer maxDownloads, int downloadCount, String keyMode, Instant revokedAt,
                            Instant lastAccessAt) {}

    /** The token is returned exactly once; only its hash is stored. The share page is {@code /s/{token}}. */
    public record CreatedLink(ShareLink link, String token) {}

    /** event: VIEWED, DOWNLOADED, WRONG_PASSWORD, LOCKED, EXPIRED, REVOKED, LIMIT_REACHED, FILE_DELETED. */
    public record AccessEntry(long id, Instant at, String event, UserSummary viewer, String visitor) {}

    /** Public metadata. For E2EE files, name and type are encrypted inside the file, so they're null here. */
    public record ShareMeta(String fileName, String contentType, long sizeBytes, boolean e2ee, String keyMode,
                            Instant expiresAt, boolean requiresPassword, Integer downloadsRemaining, String scanStatus,
                            String scanEngine) {}

    public record UnlockRequest(@Size(max = 128) String password) {}

    /** downloadUrl is a short-lived signed URL. wrappedKey is present for PASSPHRASE links (after any password check). */
    public record Unlock(String downloadUrl, Instant expiresAt, WrappedKey wrappedKey) {}

    public record DownloadUrl(String url, Instant expiresAt) {}
}
