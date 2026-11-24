package app.howlers.messaging;

import app.howlers.files.DownloadGrants;
import app.howlers.messaging.DmDtos.DmMedia;
import app.howlers.storage.ImageVariants;
import app.howlers.storage.MediaService;
import app.howlers.storage.StorageService;
import app.howlers.storage.Thumbnails;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Participant-only URLs for direct-message images (D-024, closing the D-019 gap). Images uploaded through
 * {@code POST /me/dm-media} are stored under {@code private/}, which the public media endpoint refuses. Each message
 * read hands the reader a URL signed for them ({@code /api/v1/dm-media/{grant}}), and every use re-checks that the
 * reader still is a participant of a conversation where the image is attached to a message that isn't deleted (or
 * uploaded it). Legacy attachments stored under {@code media/} keep their old public URL.
 */
@Component
public class DmMediaUrls {
    /** Grants last 30 to 60 minutes and are identical within a 30-minute window, so the browser cache keeps working. */
    static final Duration WINDOW = Duration.ofMinutes(30);

    private final DownloadGrants grants;
    private final StorageService storage;
    private final JdbcClient jdbc;

    public DmMediaUrls(DownloadGrants grants, StorageService storage, JdbcClient jdbc) {
        this.grants = grants;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public DmMedia dto(long assetId, String storageKey, Integer width, Integer height, String variantWidths, long viewerId) {
        if (!storageKey.startsWith(MediaService.PRIVATE_PREFIX + "/")) {
            String key = storageKey;
            return new DmMedia(assetId, storage.publicUrl(key), width, height,
                    Thumbnails.of(variantWidths, w -> storage.publicUrl(ImageVariants.keyFor(key, w))), false, null);
        }
        Instant exp = grants.bucketedExpiry(WINDOW);
        String url = "/api/v1/dm-media/" + grants.issue(DownloadGrants.Kind.D, assetId, viewerId, exp);
        return new DmMedia(assetId, url, width, height, Thumbnails.of(variantWidths, w -> url + "?w=" + w), true, exp);
    }

    public record Target(String storageKey, Instant expiresAt) {}

    /** The private object a grant may read right now, or empty (never says why, so ids aren't confirmed). */
    public Optional<Target> resolve(String grant) {
        Optional<DownloadGrants.Grant> g = grants.verify(grant).filter(x -> x.kind() == DownloadGrants.Kind.D && x.viewerId() != null);
        if (g.isEmpty()) return Optional.empty();
        long viewer = g.get().viewerId();
        return jdbc.sql("""
                        SELECT a.storage_key FROM media_assets a
                        WHERE a.id = :id AND a.storage_key LIKE 'private/%'
                          AND (a.owner_id = :viewer
                               OR EXISTS (SELECT 1 FROM dm_message_media mm
                                          JOIN dm_messages m ON m.id = mm.message_id AND m.deleted_at IS NULL
                                          JOIN dm_participants p ON p.conversation_id = m.conversation_id AND p.user_id = :viewer
                                          WHERE mm.media_id = a.id))""")
                .param("id", g.get().targetId()).param("viewer", viewer)
                .query(String.class).optional()
                .map(k -> new Target(k, g.get().expiresAt()));
    }
}
