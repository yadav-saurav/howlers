package app.howlers.files;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.files.FileDtos.AccessEntry;
import app.howlers.files.FileDtos.CreateLinkRequest;
import app.howlers.files.FileDtos.CreatedLink;
import app.howlers.files.FileDtos.DownloadUrl;
import app.howlers.files.FileDtos.FileItem;
import app.howlers.files.FileDtos.RenameRequest;
import app.howlers.files.FileDtos.ShareLink;
import app.howlers.files.FileDtos.ShareMeta;
import app.howlers.files.FileDtos.Unlock;
import app.howlers.files.FileDtos.UnlockRequest;
import app.howlers.files.FileDtos.Usage;
import app.howlers.files.FileService.Served;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Private files ({@code /me/files}, owner only), public share links ({@code /share/{token}}) and signed downloads
 * ({@code /downloads/{grant}}). Every file response is an attachment with nosniff and a sandboxing CSP, so even
 * HTML or SVG never renders as active content on our origin.
 */
@Tag(name = "Files")
@RestController
@RequestMapping("/api/v1")
public class FilesController {
    private final FileService files;
    private final ShareService shares;

    public FilesController(FileService files, ShareService shares) {
        this.files = files;
        this.shares = shares;
    }

    private static AuthUser require(AuthUser user) {
        if (user == null) throw ApiException.unauthorized("Authentication required");
        return user;
    }

    // ---------------------------------------------------------------- owner

    @GetMapping("/me/files")
    public CursorPage<FileItem> list(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return files.list(require(user), q, cursor, Cursors.clampLimit(limit, 30, 100));
    }

    @GetMapping("/me/files/usage")
    public Usage usage(@AuthenticationPrincipal AuthUser user) {
        return files.usage(require(user));
    }

    @PostMapping(value = "/me/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileItem upload(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file,
                           @RequestParam(defaultValue = "false") boolean e2ee, @RequestParam(required = false) String label) {
        return files.upload(require(user), file, e2ee, label);
    }

    @GetMapping("/me/files/{id}")
    public FileItem get(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return files.get(require(user), id);
    }

    @PatchMapping("/me/files/{id}")
    public FileItem rename(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody RenameRequest req) {
        return files.rename(require(user), id, req.name());
    }

    @DeleteMapping("/me/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        files.delete(require(user), id);
    }

    /** Owner download with a Bearer token (fetch); supports Range. */
    @GetMapping("/me/files/{id}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return resource(files.content(require(user), id));
    }

    /** A short-lived signed URL for a plain browser download (navigations can't carry the Bearer token). */
    @PostMapping("/me/files/{id}/download-url")
    public DownloadUrl downloadUrl(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return files.downloadUrl(require(user), id);
    }

    @GetMapping("/me/files/{id}/links")
    public List<ShareLink> links(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return shares.links(require(user), id);
    }

    @PostMapping("/me/files/{id}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedLink createLink(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody CreateLinkRequest req) {
        return shares.create(require(user), id, req);
    }

    @DeleteMapping("/me/files/{id}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @PathVariable long linkId) {
        shares.revoke(require(user), id, linkId);
    }

    @GetMapping("/me/files/{id}/links/{linkId}/access")
    public CursorPage<AccessEntry> access(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @PathVariable long linkId,
                                          @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return shares.accessLog(require(user), id, linkId, cursor, Cursors.clampLimit(limit, 50, 200));
    }

    // ---------------------------------------------------------------- public

    @GetMapping("/share/{token}")
    public ShareMeta meta(@PathVariable String token, @AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        return shares.meta(token, user, req.getRemoteAddr());
    }

    @PostMapping("/share/{token}/unlock")
    public Unlock unlock(@PathVariable String token, @RequestBody(required = false) @Valid UnlockRequest body,
                         @AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        return shares.unlock(token, body == null ? null : body.password(), user, req.getRemoteAddr());
    }

    /**
     * Signed download. Links without a download limit return a Resource, so Spring answers Range requests (206).
     * Download-limited links always send the whole file (Range ignored) so partial requests can't dodge the count.
     */
    @GetMapping("/downloads/{grant}")
    public ResponseEntity<Resource> download(@PathVariable String grant, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        ShareService.Download d = shares.download(grant, req.getHeader(HttpHeaders.RANGE), req.getRemoteAddr());
        if (d.allowRanges()) return resource(d.served());
        Served s = d.served();
        res.setStatus(HttpStatus.OK.value());
        HttpHeaders h = headers(s);
        h.forEach((k, values) -> values.forEach(v -> res.addHeader(k, v)));
        res.setContentType(contentType(s));
        res.setContentLengthLong(s.size());
        try (InputStream in = s.resource().getInputStream(); OutputStream out = res.getOutputStream()) {
            in.transferTo(out);
        }
        return null; // response written directly
    }

    // ---------------------------------------------------------------- helpers

    private static String contentType(Served s) {
        return s.e2ee() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : s.contentType();
    }

    private static HttpHeaders headers(Served s) {
        HttpHeaders h = new HttpHeaders();
        String name = s.e2ee() ? "encrypted-file.howlers" : s.name();
        h.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; sandbox");
        h.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        return h;
    }

    private static ResponseEntity<Resource> resource(Served s) {
        return ResponseEntity.ok()
                .headers(headers(s))
                .contentType(MediaType.parseMediaType(contentType(s)))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(s.resource());
    }
}
