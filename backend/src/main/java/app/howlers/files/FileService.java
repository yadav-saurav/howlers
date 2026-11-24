package app.howlers.files;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.files.FileDtos.DownloadUrl;
import app.howlers.files.FileDtos.FileItem;
import app.howlers.files.FileDtos.Usage;
import app.howlers.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Private file library. Files belong to their owner and are never reachable through the public media URL space:
 * bytes live under the {@code private/} storage prefix, which the public file endpoint refuses to serve, and every
 * read goes through an ownership check, a share link or a short-lived signed grant.
 */
@Service
public class FileService {
    static final int HEAD_BYTES = 8192;
    private static final long FILES_LOCK_NS = 0x46494C45L; // "FILE"

    private final JdbcClient jdbc;
    private final StorageService storage;
    private final FileScanner scanner;
    private final FilesProperties props;
    private final RateLimiter limiter;
    private final DownloadGrants grants;

    public FileService(JdbcClient jdbc, StorageService storage, FileScanner scanner, FilesProperties props, RateLimiter limiter,
                       DownloadGrants grants) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.scanner = scanner;
        this.props = props;
        this.limiter = limiter;
        this.grants = grants;
    }

    /** Row shape shared with ShareService. */
    record FileRow(long id, long ownerId, String name, String contentType, long size, String storageKey, String sha256,
                   boolean e2ee, String scanStatus, String scanDetail, String scanEngine, Instant createdAt, Instant updatedAt, Instant deletedAt) {}

    static final String FILE_COLUMNS = "f.id, f.owner_id, f.name, f.content_type, f.size_bytes, f.storage_key, f.sha256, f.e2ee, "
            + "f.scan_status, f.scan_detail, f.scan_engine, f.created_at, f.updated_at, f.deleted_at";

    static FileRow mapFile(ResultSet rs) throws SQLException {
        return new FileRow(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("storage_key"), rs.getString("sha256"), rs.getBoolean("e2ee"),
                rs.getString("scan_status"), rs.getString("scan_detail"), rs.getString("scan_engine"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "deleted_at"));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    // ------------------------------------------------------------------ upload

    @Transactional
    public FileItem upload(AuthUser user, MultipartFile file, boolean e2ee, String label) {
        limiter.check("files:upload:" + user.id(), 120, Duration.ofHours(1));
        if (file == null || file.isEmpty()) throw ApiException.field("file", "Choose a file to upload");
        if (file.getSize() > props.maxFileBytes()) {
            throw ApiException.field("file", "Files must be " + props.maxFileMb() + " MB or smaller");
        }
        // One upload at a time per user, so two parallel uploads can't both slip under the quota.
        jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)").params((int) FILES_LOCK_NS, Long.hashCode(user.id())).query(Object.class).list();
        Usage before = usage(user);
        if (before.usedBytes() + file.getSize() > props.quotaBytes()) {
            throw ApiException.field("file", "This upload would go over your " + human(props.quotaBytes()) + " storage quota ("
                    + human(before.usedBytes()) + " used). Delete some files first.");
        }
        String name = e2ee ? cleanLabel(label) : cleanName(file.getOriginalFilename());
        String key = "private/" + Tokens.random(24) + ".bin";
        byte[] head;
        String sha;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (PushbackInputStream in = new PushbackInputStream(new DigestInputStream(file.getInputStream(), md), HEAD_BYTES)) {
                head = in.readNBytes(HEAD_BYTES);
                in.unread(head);
                storage.put(key, (InputStream) in, "application/octet-stream"); // streamed, never buffered whole
            }
            sha = HexFormat.of().formatHex(md.digest());
        } catch (IOException | RuntimeException e) {
            storage.delete(key);
            if (e instanceof ApiException api) throw api;
            throw ApiException.badRequest("Upload failed");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        deleteObjectUnlessCommitted(key);

        Path local = storage.resource(key).filter(Resource::isFile).map(r -> {
            try {
                return r.getFile().toPath();
            } catch (IOException e) {
                return null;
            }
        }).orElse(null);
        // Object storage (S3/MinIO) has no local path; zip archives are staged to a temp file so the archive checks still run.
        Path staged = null;
        if (local == null && !e2ee && head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
            staged = stageLocally(key);
            local = staged;
        }
        FileScanner.Result scan;
        try {
            Path scanPath = local;
            String storedKey = key;
            scan = scanner.scan(new FileScanner.Input(name, head, file.getSize(), local, e2ee, () -> scanPath != null
                    ? java.nio.file.Files.newInputStream(scanPath)
                    : storage.get(storedKey).orElseThrow(() -> new IOException("stored object missing")).stream()));
        } finally {
            if (staged != null) {
                try {
                    java.nio.file.Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // temp file, best effort
                }
            }
        }
        if (scan.status() == FileScanner.Status.REJECTED) {
            storage.delete(key);
            throw ApiException.field("file", scan.reason());
        }
        long id = jdbc.sql("""
                        INSERT INTO user_files (owner_id, name, content_type, size_bytes, storage_key, sha256, e2ee, scan_status, scan_detail, scan_engine)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(user.id(), name, scan.contentType(), file.getSize(), key, sha, e2ee, scan.status().name(), truncate(scan.reason(), 300),
                        scan.engine().name())
                .query(Long.class).single();
        return get(user, id);
    }

    private Path stageLocally(String key) {
        try {
            Path tmp = java.nio.file.Files.createTempFile("howlers-scan-", ".bin");
            try (InputStream in = storage.get(key).orElseThrow(() -> new IOException("stored object missing")).stream()) {
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            storage.delete(key);
            throw ApiException.badRequest("Upload failed");
        }
    }

    /** If the transaction rolls back after the bytes were stored, remove them again. */
    private void deleteObjectUnlessCommitted(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.delete(key);
            }
        });
    }

    // ------------------------------------------------------------------ library

    public Usage usage(AuthUser user) {
        return jdbc.sql("SELECT coalesce(sum(size_bytes), 0) AS used, count(*) AS n FROM user_files WHERE owner_id = ? AND deleted_at IS NULL")
                .param(user.id())
                .query((rs, i) -> new Usage(rs.getLong("used"), props.quotaBytes(), props.maxFileBytes(), rs.getLong("n")))
                .single();
    }

    public CursorPage<FileItem> list(AuthUser user, String q, String cursor, int limit) {
        Cursors.Key k = Cursors.decode(cursor);
        StringBuilder sql = new StringBuilder("SELECT " + FILE_COLUMNS + ", " + ACTIVE_LINKS
                + " FROM user_files f WHERE f.owner_id = :me AND f.deleted_at IS NULL");
        String query = q == null ? "" : q.trim();
        if (!query.isEmpty()) sql.append(" AND f.name ILIKE :pattern ESCAPE '\\'");
        if (k != null) sql.append(" AND f.id < :before");
        sql.append(" ORDER BY f.id DESC LIMIT :lim");
        var spec = jdbc.sql(sql.toString()).param("me", user.id()).param("lim", limit + 1);
        if (!query.isEmpty()) spec = spec.param("pattern", "%" + likeEscape(query.length() > 100 ? query.substring(0, 100) : query) + "%");
        if (k != null) spec = spec.param("before", k.id());
        List<FileItem> rows = new ArrayList<>(spec.query((rs, i) -> toItem(mapFile(rs), rs.getInt("active_links"))).list());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            FileItem last = rows.get(rows.size() - 1);
            next = Cursors.encode(last.id(), last.id());
        }
        return new CursorPage<>(rows, next);
    }

    static final String ACTIVE_LINKS = """
            (SELECT count(*) FROM file_share_links l WHERE l.file_id = f.id AND l.revoked_at IS NULL
               AND (l.expires_at IS NULL OR l.expires_at > now())
               AND (l.max_downloads IS NULL OR l.download_count < l.max_downloads)) AS active_links""";

    public FileItem get(AuthUser user, long id) {
        return jdbc.sql("SELECT " + FILE_COLUMNS + ", " + ACTIVE_LINKS
                        + " FROM user_files f WHERE f.id = ? AND f.owner_id = ? AND f.deleted_at IS NULL")
                .params(id, user.id())
                .query((rs, i) -> toItem(mapFile(rs), rs.getInt("active_links")))
                .optional().orElseThrow(() -> ApiException.notFound("File"));
    }

    /** Owned, live file, or 404 (never 403, so other people's file ids aren't confirmed). */
    FileRow owned(AuthUser user, long id) {
        return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM user_files f WHERE f.id = ? AND f.owner_id = ? AND f.deleted_at IS NULL")
                .params(id, user.id()).query((rs, i) -> mapFile(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("File"));
    }

    Optional<FileRow> byId(long id) {
        return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM user_files f WHERE f.id = ?").param(id).query((rs, i) -> mapFile(rs)).optional();
    }

    @Transactional
    public FileItem rename(AuthUser user, long id, String name) {
        FileRow f = owned(user, id);
        String clean = f.e2ee() ? cleanLabel(name) : cleanName(name);
        if (!f.e2ee()) {
            // Keep the extension consistent with the content that was checked at upload.
            String oldExt = FileTypes.extension(f.name());
            String newExt = FileTypes.extension(clean);
            if (!oldExt.equals(newExt)) {
                throw ApiException.field("name", oldExt.isEmpty() ? "Keep the name without an extension" : "Keep the ." + oldExt + " extension");
            }
        }
        jdbc.sql("UPDATE user_files SET name = ?, updated_at = now() WHERE id = ?").params(clean, id).update();
        return get(user, id);
    }

    /** Soft-deletes the row (links stop working at once), then removes the stored bytes after commit. */
    @Transactional
    public void delete(AuthUser user, long id) {
        FileRow f = owned(user, id);
        jdbc.sql("UPDATE user_files SET deleted_at = now(), updated_at = now() WHERE id = ?").param(id).update();
        jdbc.sql("UPDATE file_share_links SET revoked_at = now() WHERE file_id = ? AND revoked_at IS NULL").param(id).update();
        String key = f.storageKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storage.delete(key);
                }
            });
        } else {
            storage.delete(key);
        }
    }

    // ------------------------------------------------------------------ owner downloads

    public record Served(Resource resource, String name, String contentType, long size, boolean e2ee) {}

    public Served content(AuthUser user, long id) {
        return serve(owned(user, id));
    }

    Served serve(FileRow f) {
        Resource r = storage.resource(f.storageKey()).orElseThrow(() -> ApiException.notFound("File"));
        return new Served(r, f.name(), f.contentType(), f.size(), f.e2ee());
    }

    public DownloadUrl downloadUrl(AuthUser user, long id) {
        owned(user, id);
        Instant exp = grants.expiry();
        return new DownloadUrl("/api/v1/downloads/" + grants.issue(DownloadGrants.Kind.O, id, user.id(), exp), exp);
    }

    // ------------------------------------------------------------------ helpers

    FileItem toItem(FileRow f, int activeLinks) {
        return new FileItem(f.id(), f.name(), f.contentType(), f.size(), f.e2ee(), f.scanStatus(), f.scanDetail(), f.scanEngine(), f.sha256(),
                activeLinks, f.createdAt(), f.updatedAt());
    }

    /** Last path segment only, no control or path characters, at most 255 chars (keeping the extension). */
    static String cleanName(String raw) {
        String n = raw == null ? "" : raw;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "").replace(':', '_').trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank()) throw ApiException.field("name", "Give the file a name");
        if (n.length() > 255) {
            String ext = FileTypes.extension(n);
            n = n.substring(0, 255 - (ext.isEmpty() ? 0 : ext.length() + 1)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    static String cleanLabel(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069/\\\\]", "").trim();
        if (n.isEmpty()) return "Encrypted file";
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " bytes";
    }
}
