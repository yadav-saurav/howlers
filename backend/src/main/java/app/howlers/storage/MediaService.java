package app.howlers.storage;

import app.howlers.antivirus.Antivirus;
import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import app.howlers.common.text.Tokens;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    /** DM attachments live under the private prefix: never served by the public {@code /files/**} endpoint (D-024). */
    public static final String PRIVATE_PREFIX = "private";

    private final StorageService storage;
    private final MediaAssetRepository assets;
    private final long maxBytes;
    private final Antivirus antivirus;

    public record MediaDto(long id, String url, Integer width, Integer height, String contentType, Thumbnails thumbnails) {}

    public record StoredImage(String key, String url, ImageInspector.ImageInfo info, long size, String variantWidths) {}

    public MediaService(StorageService storage, MediaAssetRepository assets, AppProperties props, Antivirus antivirus) {
        this.storage = storage;
        this.assets = assets;
        this.antivirus = antivirus;
        this.maxBytes = props.storage().maxImageBytes();
    }

    /**
     * Validates and stores an image under {@code prefix}/ with a random, unguessable key, plus its resized variants
     * ({@link ImageVariants}) next to it.
     */
    public StoredImage storeImage(MultipartFile file, String prefix, long limitBytes) {
        if (file == null || file.isEmpty()) throw ApiException.field("file", "Choose a file to upload");
        long limit = Math.min(limitBytes, maxBytes);
        if (file.getSize() > limit) throw ApiException.field("file", "Images must be under " + (limit / 1024 / 1024) + " MB");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Upload failed");
        }
        ImageInspector.Decoded decoded = ImageInspector.decode(bytes);
        ImageInspector.ImageInfo info = decoded.info();
        // The stored original carries no GPS/camera/XMP metadata (D-028); pixels are untouched (lossless segment removal).
        try {
            bytes = ImageMetadata.strip(bytes, info.extension());
        } catch (ImageMetadata.MalformedImageException e) {
            throw ApiException.field("file", "The image could not be read");
        }
        antivirus.check(bytes, "image");
        String key = prefix + "/" + Tokens.random(18) + "." + info.extension();
        storage.put(key, bytes, info.contentType());
        String widths = storeVariants(key, bytes, decoded);
        return new StoredImage(key, storage.publicUrl(key), info, bytes.length, widths);
    }

    /** Variants are an optimisation: if resizing fails, the upload still succeeds with the original only. */
    private String storeVariants(String key, byte[] bytes, ImageInspector.Decoded decoded) {
        List<ImageVariants.Variant> stored = new ArrayList<>();
        try {
            for (ImageVariants.Variant v : ImageVariants.generate(bytes, decoded)) {
                storage.put(ImageVariants.keyFor(key, v.width()), v.bytes(), v.contentType());
                stored.add(v);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            log.warn("Could not create image variants for {}: {}", key, e.toString());
        }
        return Thumbnails.widths(stored);
    }

    @Transactional
    public MediaDto uploadPostImage(long ownerId, MultipartFile file) {
        StoredImage img = storeImage(file, "media", maxBytes);
        MediaAsset a = assets.save(new MediaAsset(ownerId, img.key(), img.info().contentType(), img.size(),
                img.info().width(), img.info().height(), img.variantWidths()));
        return toDto(a);
    }

    /** A direct-message image: stored under {@code private/}, readable only through participant-checked grants. */
    @Transactional
    public MediaAsset uploadPrivateImage(long ownerId, MultipartFile file) {
        StoredImage img = storeImage(file, PRIVATE_PREFIX, maxBytes);
        return assets.save(new MediaAsset(ownerId, img.key(), img.info().contentType(), img.size(),
                img.info().width(), img.info().height(), img.variantWidths()));
    }

    /** Voice notes in direct messages and pack rooms: at most 5 MB and 5 minutes. */
    public static final long MAX_VOICE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_VOICE_MS = 300_000;
    private static final java.util.Set<String> VOICE_TYPES = java.util.Set.of("audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg");

    private record Audio(String extension, String contentType) {}

    /**
     * A direct-message voice note: stored under {@code private/} with a random key, like DM images. The declared type
     * must be one of WebM, Ogg, MP4 or MPEG audio, and the leading bytes must be one of those containers; the stored
     * type comes from the bytes, never from the client.
     */
    @Transactional
    public MediaAsset uploadPrivateAudio(long ownerId, MultipartFile file, Integer durationMs) {
        if (file == null || file.isEmpty()) throw ApiException.field("file", "Record a voice note to send");
        if (file.getSize() > MAX_VOICE_BYTES) throw ApiException.field("file", "Voice notes must be under 5 MB");
        String declared = file.getContentType() == null ? "" : file.getContentType().split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
        if (!VOICE_TYPES.contains(declared)) throw ApiException.field("file", "Voice notes must be WebM, Ogg, MP4 or MP3 audio");
        if (durationMs != null && (durationMs < 1 || durationMs > MAX_VOICE_MS)) {
            throw ApiException.field("durationMs", "Voice notes can be up to 5 minutes long");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Upload failed");
        }
        Audio audio = sniffAudio(bytes);
        antivirus.check(bytes, "voice note");
        String key = PRIVATE_PREFIX + "/" + Tokens.random(18) + "." + audio.extension();
        storage.put(key, bytes, audio.contentType());
        MediaAsset a = new MediaAsset(ownerId, key, audio.contentType(), bytes.length, null, null);
        a.setDurationMs(durationMs);
        return assets.save(a);
    }

    private static Audio sniffAudio(byte[] h) {
        if (h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            // EBML: accept the WebM doc type only (not arbitrary Matroska).
            String head = new String(h, 0, Math.min(h.length, 64), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (head.contains("webm")) return new Audio("weba", "audio/webm");
        }
        if (h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return new Audio("ogg", "audio/ogg");
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return new Audio("m4a", "audio/mp4");
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return new Audio("mp3", "audio/mpeg");
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return new Audio("mp3", "audio/mpeg");
        throw ApiException.field("file", "Voice notes must be WebM, Ogg, MP4 or MP3 audio");
    }

    public MediaDto toDto(MediaAsset a) {
        String key = a.getStorageKey();
        return new MediaDto(a.getId(), storage.publicUrl(key), a.getWidth(), a.getHeight(), a.getContentType(),
                Thumbnails.of(a.getVariantWidths(), w -> storage.publicUrl(ImageVariants.keyFor(key, w))));
    }

    /**
     * The key to serve for {@code ?w=}: the variant when it exists, otherwise the original. Lets avatars, banners
     * and icons (stored as plain URLs) ask for a small copy without knowing whether one was made.
     */
    public String variantOrOriginal(String key, Integer width) {
        if (width == null || (width != 320 && width != 960)) return key;
        int dot = key.lastIndexOf('.');
        if (dot < 0 || !ImageVariants.supports(key.substring(dot + 1))) return key;
        String v = ImageVariants.keyFor(key, width);
        return storage.exists(v) ? v : key;
    }
}
