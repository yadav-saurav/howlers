package app.howlers.messaging;

import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.DmMedia;
import app.howlers.storage.MediaAsset;
import app.howlers.storage.MediaService;
import app.howlers.storage.StorageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Private direct-message images (D-024): upload under {@code private/}, read through participant-signed grants. */
@Tag(name = "Messages")
@RestController
public class DmMediaController {
    private final MediaService media;
    private final DmMediaUrls urls;
    private final StorageService storage;
    private final RateLimiter limiter;

    public DmMediaController(MediaService media, DmMediaUrls urls, StorageService storage, RateLimiter limiter) {
        this.media = media;
        this.urls = urls;
        this.storage = storage;
        this.limiter = limiter;
    }

    @PostMapping(value = "/api/v1/me/dm-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DmMedia upload(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        limiter.check("dm-media:" + user.id(), 60, Duration.ofHours(1));
        MediaAsset a = media.uploadPrivateImage(user.id(), file);
        return urls.dto(a.getId(), a.getStorageKey(), a.getWidth(), a.getHeight(), a.getVariantWidths(), user.id());
    }

    /**
     * Public route (an {@code <img>} can't send the Bearer token); the signed grant names the viewer and is re-checked
     * against the conversation on every request. {@code ?w=320|960} serves a resized variant when one exists.
     */
    @GetMapping("/api/v1/dm-media/{grant}")
    public ResponseEntity<Resource> read(@PathVariable String grant, @RequestParam(name = "w", required = false) Integer w) {
        return urls.resolve(grant)
                .flatMap(t -> storage.resource(media.variantOrOriginal(t.storageKey(), w)).map(r -> {
                    long maxAge = Math.max(0, t.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(StorageService.contentTypeFor(t.storageKey())))
                            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(maxAge)).cachePrivate())
                            .header("X-Content-Type-Options", "nosniff")
                            .header("Content-Security-Policy", "default-src 'none'")
                            .header("Referrer-Policy", "no-referrer")
                            .body(r);
                }))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
