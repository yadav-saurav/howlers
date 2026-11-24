package app.howlers.storage;

import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

/**
 * Binary storage abstraction. Metadata lives in PostgreSQL; bytes live here. The local filesystem driver
 * is used in development and single-node deployments; {@link S3StorageService} talks to any S3-compatible
 * service (see docs/DECISIONS.md, D-007 and D-023). The driver is chosen by {@code howlers.storage.driver}.
 */
public interface StorageService {
    /**
     * Keys are generated server-side ({@code folder/random.ext}, image variants add {@code _w320} etc.); this guards
     * against traversal or odd keys if one is ever user-influenced.
     */
    Pattern SAFE_KEY = Pattern.compile("^[a-z]+/[A-Za-z0-9_-]{8,72}\\.(png|jpg|webp|gif|mp3|m4a|ogg|wav|mp4|webm|bin)$");

    static boolean isSafeKey(String key) {
        return key != null && SAFE_KEY.matcher(key).matches();
    }

    void put(String key, byte[] bytes, String contentType);

    /** Streams large objects (audio/video) without buffering them in memory. */
    void put(String key, InputStream in, String contentType);

    Optional<StoredObject> get(String key);

    /**
     * The object as a seekable Resource with a known length. Returning a Resource (not a raw stream) lets Spring MVC
     * answer HTTP Range requests, which media players need for seeking.
     */
    Optional<Resource> resource(String key);

    void delete(String key);

    default boolean exists(String key) {
        return resource(key).isPresent();
    }

    /** Public URL path for a stored key, relative to the API origin. */
    default String publicUrl(String key) {
        return "/api/v1/files/" + key;
    }

    boolean healthy();

    record StoredObject(InputStream stream, long size, String contentType) {}

    static String contentTypeFor(String key) {
        int dot = key.lastIndexOf('.');
        String ext = dot < 0 ? "" : key.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }
}
