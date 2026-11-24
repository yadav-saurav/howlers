package app.howlers.storage;

import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.storage.MediaService.MediaDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Media")
@RestController
public class MediaController {
    private final MediaService media;
    private final StorageService storage;
    private final RateLimiter limiter;

    public MediaController(MediaService media, StorageService storage, RateLimiter limiter) {
        this.media = media;
        this.storage = storage;
        this.limiter = limiter;
    }

    @PostMapping(value = "/api/v1/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaDto upload(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        limiter.check("media:" + user.id(), 30, Duration.ofHours(1));
        return media.uploadPostImage(user.id(), file);
    }

    /**
     * Public, immutable media. Keys are random, so URLs are unguessable but not access-controlled.
     * Returning a seekable Resource lets Spring answer Range requests (206 Partial Content) for audio/video seeking.
     */
    @GetMapping("/api/v1/files/**")
    public ResponseEntity<Resource> file(HttpServletRequest req, @RequestParam(name = "w", required = false) Integer w) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String requested = path.substring("/api/v1/files/".length());
        // Private files (Phase 7) and DM images (D-024) are never served here; they go through ownership checks,
        // share links or participant-checked grants.
        if (requested.startsWith("private/") || !StorageService.isSafeKey(requested)) return ResponseEntity.notFound().build();
        // ?w=320|960 serves the resized variant when one exists, else the original (D-024).
        String key = media.variantOrOriginal(requested, w);
        return storage.resource(key)
                .map(r -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(StorageService.contentTypeFor(key)))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'")
                        .header("Accept-Ranges", "bytes")
                        .body(r))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
