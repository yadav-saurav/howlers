package app.howlers.files;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.files.FileDtos.AccessEntry;
import app.howlers.files.FileDtos.CreateLinkRequest;
import app.howlers.files.FileDtos.CreatedLink;
import app.howlers.files.FileDtos.ShareLink;
import app.howlers.files.FileDtos.ShareMeta;
import app.howlers.files.FileDtos.Unlock;
import app.howlers.files.FileDtos.WrappedKey;
import app.howlers.files.FileService.FileRow;
import app.howlers.files.FileService.Served;
import app.howlers.user.UserDirectory;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Share links for private files. Tokens are random (192 bits) and stored only as SHA-256. Every public request
 * re-checks the link (revoked, expired, download limit, file deleted) and records an access-log row with a coarse
 * viewer: the signed-in user or "anonymous" plus a daily, salted, truncated hash of the IP (never the IP itself).
 *
 * <p>Public methods are deliberately not transactional: failed attempts must be logged even though the request fails.
 */
@Service
public class ShareService {
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{20,64}$");
    static final int MAX_FAILURES_PER_VISITOR = 5;   // per 15 minutes
    static final int MAX_FAILURES_PER_LINK = 50;     // per hour, across all visitors

    private final JdbcClient jdbc;
    private final FileService files;
    private final PasswordEncoder encoder;
    private final DownloadGrants grants;
    private final RateLimiter limiter;
    private final UserDirectory users;
    private final byte[] visitorSalt;

    public ShareService(JdbcClient jdbc, FileService files, PasswordEncoder encoder, DownloadGrants grants, RateLimiter limiter,
                        UserDirectory users, FilesProperties props) {
        this.jdbc = jdbc;
        this.files = files;
        this.encoder = encoder;
        this.grants = grants;
        this.limiter = limiter;
        this.users = users;
        if (props.visitorSalt() != null && !props.visitorSalt().isBlank()) {
            this.visitorSalt = props.visitorSalt().getBytes(StandardCharsets.UTF_8);
        } else {
            this.visitorSalt = new byte[32];
            new SecureRandom().nextBytes(this.visitorSalt);
        }
    }

    record LinkRow(long id, long fileId, Instant createdAt, Instant expiresAt, String passwordHash, Integer maxDownloads,
                   int downloadCount, String keyMode, String wrappedKey, String kdfSalt, Integer kdfIterations,
                   Instant revokedAt, String tokenHint, Instant lastAccessAt) {
        String status(Instant now) {
            if (revokedAt != null) return "REVOKED";
            if (expiresAt != null && !expiresAt.isAfter(now)) return "EXPIRED";
            if (maxDownloads != null && downloadCount >= maxDownloads) return "EXHAUSTED";
            return "ACTIVE";
        }
    }

    private static final String LINK_COLUMNS = """
            l.id, l.file_id, l.created_at, l.expires_at, l.password_hash, l.max_downloads, l.download_count, l.key_mode,
            l.wrapped_key, l.kdf_salt, l.kdf_iterations, l.revoked_at, l.token_hint,
            (SELECT max(a.created_at) FROM file_share_access a WHERE a.link_id = l.id) AS last_access_at""";

    private static LinkRow mapLink(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LinkRow(rs.getLong("id"), rs.getLong("file_id"), FileService.instant(rs, "created_at"),
                FileService.instant(rs, "expires_at"), rs.getString("password_hash"), (Integer) rs.getObject("max_downloads"),
                rs.getInt("download_count"), rs.getString("key_mode"), rs.getString("wrapped_key"), rs.getString("kdf_salt"),
                (Integer) rs.getObject("kdf_iterations"), FileService.instant(rs, "revoked_at"), rs.getString("token_hint"),
                FileService.instant(rs, "last_access_at"));
    }

    private ShareLink toDto(LinkRow l) {
        return new ShareLink(l.id(), l.fileId(), l.tokenHint(), l.status(Instant.now()), l.createdAt(), l.expiresAt(),
                l.passwordHash() != null, l.maxDownloads(), l.downloadCount(), l.keyMode(), l.revokedAt(), l.lastAccessAt());
    }

    // ------------------------------------------------------------------ owner side

    public CreatedLink create(AuthUser user, long fileId, CreateLinkRequest req) {
        limiter.check("files:links:" + user.id(), 120, Duration.ofHours(1));
        FileRow f = files.owned(user, fileId);
        Instant now = Instant.now();
        if (req.expiresAt() != null) {
            if (req.expiresAt().isBefore(now.plusSeconds(60))) throw ApiException.field("expiresAt", "Pick an expiry in the future");
            if (req.expiresAt().isAfter(now.plus(Duration.ofDays(366)))) throw ApiException.field("expiresAt", "Links can last at most a year");
        }
        String keyMode = req.keyMode() == null || req.keyMode().isBlank() ? null : req.keyMode().trim().toUpperCase();
        WrappedKey wk = req.wrappedKey();
        if (f.e2ee()) {
            if (!"FRAGMENT".equals(keyMode) && !"PASSPHRASE".equals(keyMode)) {
                throw ApiException.field("keyMode", "Choose how the recipient gets the key: in the link or with a passphrase");
            }
            if ("PASSPHRASE".equals(keyMode)) {
                if (wk == null) throw ApiException.field("wrappedKey", "A passphrase link needs the wrapped key");
                checkB64(wk.salt(), 16, 64, "salt");
                checkB64(wk.wrappedKey(), 44, 100, "wrappedKey");
            } else if (wk != null) {
                throw ApiException.field("wrappedKey", "Links with the key in the URL don't send a wrapped key");
            }
        } else if (keyMode != null || wk != null) {
            throw ApiException.field("keyMode", "Only end-to-end encrypted files use key modes");
        }
        String password = req.password() == null || req.password().isEmpty() ? null : req.password();
        String token = Tokens.random(24);
        long id = jdbc.sql("""
                        INSERT INTO file_share_links (file_id, created_by, token_hash, token_hint, expires_at, password_hash, max_downloads,
                                                      key_mode, wrapped_key, kdf_salt, kdf_iterations)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(fileId, user.id(), Tokens.sha256(token), token.substring(token.length() - 4),
                        req.expiresAt() == null ? null : req.expiresAt().atOffset(ZoneOffset.UTC),
                        password == null ? null : encoder.encode(password), req.maxDownloads(), keyMode,
                        "PASSPHRASE".equals(keyMode) ? wk.wrappedKey() : null,
                        "PASSPHRASE".equals(keyMode) ? wk.salt() : null,
                        "PASSPHRASE".equals(keyMode) ? wk.iterations() : null)
                .query(Long.class).single();
        return new CreatedLink(toDto(linkById(id)), token);
    }

    private static void checkB64(String v, int minBytes, int maxBytes, String field) {
        try {
            int n = Base64.getUrlDecoder().decode(v).length;
            if (n < minBytes || n > maxBytes) throw ApiException.field(field, "Invalid " + field);
        } catch (IllegalArgumentException e) {
            throw ApiException.field(field, "Invalid " + field);
        }
    }

    public List<ShareLink> links(AuthUser user, long fileId) {
        files.owned(user, fileId);
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM file_share_links l WHERE l.file_id = ? ORDER BY l.id DESC")
                .param(fileId).query((rs, i) -> mapLink(rs)).list().stream().map(this::toDto).toList();
    }

    public void revoke(AuthUser user, long fileId, long linkId) {
        files.owned(user, fileId);
        int n = jdbc.sql("UPDATE file_share_links SET revoked_at = coalesce(revoked_at, now()) WHERE id = ? AND file_id = ?")
                .params(linkId, fileId).update();
        if (n == 0) throw ApiException.notFound("Link");
    }

    public CursorPage<AccessEntry> accessLog(AuthUser user, long fileId, long linkId, String cursor, int limit) {
        files.owned(user, fileId);
        Integer exists = jdbc.sql("SELECT 1 FROM file_share_links WHERE id = ? AND file_id = ?").params(linkId, fileId)
                .query(Integer.class).optional().orElse(null);
        if (exists == null) throw ApiException.notFound("Link");
        Cursors.Key k = Cursors.decode(cursor);
        record Row(long id, Instant at, String event, Long viewerId, String visitor) {}
        List<Row> rows = new ArrayList<>(jdbc.sql("""
                        SELECT id, created_at, event, viewer_id, visitor_hash FROM file_share_access
                        WHERE link_id = :link AND (:before = 0 OR id < :before) ORDER BY id DESC LIMIT :lim""")
                .param("link", linkId).param("before", k == null ? 0L : k.id()).param("lim", limit + 1)
                .query((rs, i) -> new Row(rs.getLong("id"), FileService.instant(rs, "created_at"), rs.getString("event"),
                        (Long) rs.getObject("viewer_id"), rs.getString("visitor_hash")))
                .list());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursors.encode(rows.get(rows.size() - 1).id(), rows.get(rows.size() - 1).id());
        }
        Map<Long, UserSummary> people = users.summaries(rows.stream().map(Row::viewerId).toList());
        List<AccessEntry> items = rows.stream().map(r -> new AccessEntry(r.id(), r.at(), r.event(),
                r.viewerId() == null ? null : people.get(r.viewerId()), r.visitor())).toList();
        return new CursorPage<>(items, next);
    }

    private LinkRow linkById(long id) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM file_share_links l WHERE l.id = ?").param(id)
                .query((rs, i) -> mapLink(rs)).optional().orElseThrow(() -> ApiException.notFound("Link"));
    }

    // ------------------------------------------------------------------ public side

    private LinkRow linkByToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw ApiException.notFound("Link");
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM file_share_links l WHERE l.token_hash = ?").param(Tokens.sha256(token))
                .query((rs, i) -> mapLink(rs)).optional().orElseThrow(() -> ApiException.notFound("Link"));
    }

    private static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, "GONE", message);
    }

    /** Throws (after logging) when the link can't be used right now. Returns the live file otherwise. */
    private FileRow requireUsable(LinkRow l, Long viewerId, String visitor) {
        FileRow f = files.byId(l.fileId()).orElseThrow(() -> ApiException.notFound("Link"));
        if (f.deletedAt() != null) {
            log(l.id(), viewerId, visitor, "FILE_DELETED");
            throw gone("The owner deleted this file.");
        }
        switch (l.status(Instant.now())) {
            case "REVOKED" -> {
                log(l.id(), viewerId, visitor, "REVOKED");
                throw gone("The owner turned this link off.");
            }
            case "EXPIRED" -> {
                log(l.id(), viewerId, visitor, "EXPIRED");
                throw gone("This link has expired.");
            }
            case "EXHAUSTED" -> {
                log(l.id(), viewerId, visitor, "LIMIT_REACHED");
                throw gone("This link reached its download limit.");
            }
            default -> {
                return f;
            }
        }
    }

    public ShareMeta meta(String token, AuthUser viewer, String ip) {
        String visitor = visitorHash(ip);
        limiter.check("share:meta:" + visitor, 120, Duration.ofMinutes(15));
        LinkRow l = linkByToken(token);
        Long viewerId = viewer == null ? null : viewer.id();
        FileRow f = requireUsable(l, viewerId, visitor);
        // One VIEWED row per visitor and link per 10 minutes, so reloading the page doesn't flood the log.
        Integer recent = jdbc.sql("""
                        SELECT 1 FROM file_share_access WHERE link_id = ? AND event = 'VIEWED' AND visitor_hash IS NOT DISTINCT FROM ?
                          AND viewer_id IS NOT DISTINCT FROM ? AND created_at > now() - interval '10 minutes' LIMIT 1""")
                .params(l.id(), visitor, viewerId).query(Integer.class).optional().orElse(null);
        if (recent == null) log(l.id(), viewerId, visitor, "VIEWED");
        Integer remaining = l.maxDownloads() == null ? null : Math.max(0, l.maxDownloads() - l.downloadCount());
        return new ShareMeta(f.e2ee() ? null : f.name(), f.e2ee() ? null : f.contentType(), f.size(), f.e2ee(), l.keyMode(),
                l.expiresAt(), l.passwordHash() != null, remaining, f.scanStatus(), f.scanEngine());
    }

    public Unlock unlock(String token, String password, AuthUser viewer, String ip) {
        String visitor = visitorHash(ip);
        limiter.check("share:unlock:" + visitor, 30, Duration.ofMinutes(15));
        LinkRow l = linkByToken(token);
        Long viewerId = viewer == null ? null : viewer.id();
        requireUsable(l, viewerId, visitor);
        if (l.passwordHash() != null) {
            long mine = jdbc.sql("""
                            SELECT count(*) FROM file_share_access WHERE link_id = ? AND event = 'WRONG_PASSWORD'
                              AND visitor_hash IS NOT DISTINCT FROM ? AND created_at > now() - interval '15 minutes'""")
                    .params(l.id(), visitor).query(Long.class).single();
            long all = jdbc.sql("""
                            SELECT count(*) FROM file_share_access WHERE link_id = ? AND event = 'WRONG_PASSWORD'
                              AND created_at > now() - interval '1 hour'""")
                    .param(l.id()).query(Long.class).single();
            if (mine >= MAX_FAILURES_PER_VISITOR || all >= MAX_FAILURES_PER_LINK) {
                log(l.id(), viewerId, visitor, "LOCKED");
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many wrong passwords. Try again in 15 minutes.");
            }
            if (password == null || password.isEmpty() || !encoder.matches(password, l.passwordHash())) {
                log(l.id(), viewerId, visitor, "WRONG_PASSWORD");
                throw ApiException.field("password", "That password isn't right.");
            }
        }
        Instant exp = grants.expiry();
        String url = "/api/v1/downloads/" + grants.issue(DownloadGrants.Kind.L, l.id(), viewerId, exp);
        WrappedKey wk = "PASSPHRASE".equals(l.keyMode()) ? new WrappedKey(l.wrappedKey(), l.kdfSalt(), l.kdfIterations()) : null;
        return new Unlock(url, exp, wk);
    }

    /** What to stream for a grant. countedRanges=false means: ignore Range and send the whole file (download-limited links). */
    public record Download(Served served, boolean allowRanges) {}

    public Download download(String grant, String rangeHeader, String ip) {
        DownloadGrants.Grant g = grants.verify(grant).orElseThrow(() -> ApiException.notFound("Download"));
        if (g.kind() == DownloadGrants.Kind.O) {
            FileRow f = files.byId(g.targetId()).filter(x -> x.deletedAt() == null && g.viewerId() != null && x.ownerId() == g.viewerId())
                    .orElseThrow(() -> ApiException.notFound("File"));
            return new Download(files.serve(f), true);
        }
        // Other grant kinds (D = DM images) are only valid on their own endpoint.
        if (g.kind() != DownloadGrants.Kind.L) throw ApiException.notFound("Download");
        String visitor = visitorHash(ip);
        LinkRow l = linkById(g.targetId());
        FileRow f = requireUsable(l, g.viewerId(), visitor);
        boolean limited = l.maxDownloads() != null;
        // Range continuations (bytes=N- with N > 0) of an unlimited link aren't counted as new downloads.
        boolean counts = limited || rangeHeader == null || rangeHeader.replace(" ", "").startsWith("bytes=0-");
        if (counts) {
            int n = jdbc.sql("""
                            UPDATE file_share_links SET download_count = download_count + 1
                            WHERE id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())
                              AND (max_downloads IS NULL OR download_count < max_downloads)""")
                    .param(l.id()).update();
            if (n == 0) { // lost a race with the last allowed download, a revoke or the expiry
                requireUsable(linkById(l.id()), g.viewerId(), visitor);
                throw gone("This link can't be used any more.");
            }
            log(l.id(), g.viewerId(), visitor, "DOWNLOADED");
        }
        return new Download(files.serve(f), !limited);
    }

    private void log(long linkId, Long viewerId, String visitor, String event) {
        jdbc.sql("INSERT INTO file_share_access (link_id, viewer_id, visitor_hash, event) VALUES (?, ?, ?, ?)")
                .params(linkId, viewerId, visitor, event).update();
    }

    /** Truncated HMAC-SHA256 of (UTC day, IP) with a server-side salt: groups a visitor's attempts for one day only. */
    String visitorHash(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(visitorSalt, "HmacSHA256"));
            byte[] h = mac.doFinal((LocalDate.now(ZoneOffset.UTC) + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
