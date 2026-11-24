package app.howlers.messaging;

import app.howlers.storage.ImageVariants;
import app.howlers.storage.MediaService;
import app.howlers.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves direct-message images sent before D-024 from the public {@code media/} prefix to {@code private/} (D-030).
 *
 * <p>A candidate is a {@code media/} asset attached to at least one DM and used nowhere public: not in a post's
 * gallery, and its key doesn't appear in a link post's URL, a post or comment body, an avatar, a banner or a community
 * icon (if it does, someone already published it, so it stays public and is counted as skipped). For each candidate
 * the original and its variants are copied to {@code private/<same name>}, the row's key is switched in one
 * transaction (the key prefix is what makes {@link DmMediaUrls} issue participant-signed URLs), and the old objects
 * are deleted after commit, so the old {@code /files/media/...} URL answers 404.
 *
 * <p>Idempotent: the target key is derived from the old one, a moved row no longer matches, rows are locked with
 * {@code FOR UPDATE SKIP LOCKED} (safe with several nodes), and an object that fails to copy leaves its row
 * untouched for the next run. Runs at startup ({@code howlers.privacy.migrate-legacy-dm-media}) and on demand from the
 * admin API; every run is recorded in {@code maintenance_runs}.
 */
@Component
public class LegacyDmMedia {
    private static final Logger log = LoggerFactory.getLogger(LegacyDmMedia.class);
    public static final String JOB = "legacy-dm-media-private";
    private static final String PUBLIC_PREFIX = "media/";

    /** A public asset attached to a DM and not in any post gallery. */
    static final String IN_DMS_ONLY = """
            a.storage_key LIKE 'media/%'
            AND EXISTS (SELECT 1 FROM dm_message_media mm WHERE mm.media_id = a.id)
            AND NOT EXISTS (SELECT 1 FROM post_media pm WHERE pm.media_id = a.id)""";

    /** Its key appears somewhere public, so moving it would break a published link. */
    static final String PUBLISHED_ELSEWHERE = """
            (EXISTS (SELECT 1 FROM posts p WHERE strpos(coalesce(p.url, ''), a.storage_key) > 0
                                              OR strpos(p.body_html, a.storage_key) > 0)
             OR EXISTS (SELECT 1 FROM comments c WHERE strpos(c.body, a.storage_key) > 0)
             OR EXISTS (SELECT 1 FROM users u WHERE strpos(coalesce(u.avatar_url, ''), a.storage_key) > 0
                                              OR strpos(coalesce(u.banner_url, ''), a.storage_key) > 0)
             OR EXISTS (SELECT 1 FROM communities co WHERE strpos(coalesce(co.icon_url, ''), a.storage_key) > 0
                                                   OR strpos(coalesce(co.banner_url, ''), a.storage_key) > 0))""";

    public record Run(long id, String job, Long triggeredBy, Instant startedAt, Instant finishedAt, int candidates, int moved,
                      int skipped, int failed, String detail) {}

    private record Candidate(long id, String key, String variantWidths) {}

    private enum Outcome { MOVED, SKIPPED, FAILED, GONE }

    private final JdbcClient jdbc;
    private final StorageService storage;
    private final TransactionTemplate tx;
    private final boolean atStartup;

    public LegacyDmMedia(JdbcClient jdbc, StorageService storage, TransactionTemplate tx,
                         @Value("${howlers.privacy.migrate-legacy-dm-media:true}") boolean atStartup) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.tx = tx;
        this.atStartup = atStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!atStartup) return;
        long pending = pendingCount();
        if (pending == 0) return; // nothing to do: don't add a run row on every start
        Run r = run(null);
        log.info("Legacy DM images: moved {}, skipped {} (published elsewhere), failed {}", r.moved(), r.skipped(), r.failed());
    }

    /** Public DM-only assets still waiting to be moved (published-elsewhere ones included). */
    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM media_assets a WHERE " + IN_DMS_ONLY + " AND NOT " + PUBLISHED_ELSEWHERE)
                .query(Long.class).single();
    }

    /** Runs the job once and records it. {@code adminId} is null when started by the application. */
    public Run run(Long adminId) {
        long runId = jdbc.sql("INSERT INTO maintenance_runs (job, triggered_by) VALUES (?, ?) RETURNING id")
                .params(JOB, adminId).query(Long.class).single();
        int candidates = 0, moved = 0, skipped = 0, failed = 0;
        long after = 0;
        while (true) {
            List<Long> ids = jdbc.sql("SELECT a.id FROM media_assets a WHERE " + IN_DMS_ONLY + " AND a.id > ? ORDER BY a.id LIMIT 100")
                    .param(after).query(Long.class).list();
            if (ids.isEmpty()) break;
            for (long id : ids) {
                after = id;
                Outcome o = tx.execute(s -> moveOne(id));
                if (o == Outcome.GONE) continue;
                candidates++;
                switch (o) {
                    case MOVED -> moved++;
                    case SKIPPED -> skipped++;
                    default -> failed++;
                }
            }
        }
        String detail = skipped > 0 ? skipped + " image(s) stay public because their URL was also published in a post, comment or profile." : null;
        jdbc.sql("""
                        UPDATE maintenance_runs SET finished_at = now(), candidates = ?, moved = ?, skipped = ?, failed = ?, detail = ?
                        WHERE id = ?""")
                .params(candidates, moved, skipped, failed, detail, runId).update();
        return runs(1).get(0);
    }

    /**
     * Moves the given assets if they are DM-only and public. Used by the send path, which still accepts public uploads
     * for compatibility, so new messages can't reintroduce public DM images. Must run inside a transaction.
     */
    public void privatize(Collection<Long> assetIds) {
        if (assetIds.isEmpty()) return;
        List<Long> ids = jdbc.sql("SELECT a.id FROM media_assets a WHERE a.id IN (:ids) AND " + IN_DMS_ONLY)
                .param("ids", assetIds).query(Long.class).list();
        for (long id : ids) {
            // A failed copy (e.g. the object is missing) leaves the image where it was: sending still works, nothing
            // becomes more public than it already was, and the startup/admin job retries it.
            if (moveOne(id) == Outcome.FAILED) log.warn("DM image {} stays public for now; the maintenance job will retry", id);
        }
    }

    /** Inside a transaction: lock the row, copy objects, switch the key; old objects go after commit. */
    private Outcome moveOne(long id) {
        Optional<Candidate> row = jdbc.sql("SELECT a.id, a.storage_key, a.variant_widths FROM media_assets a WHERE a.id = ? AND "
                        + IN_DMS_ONLY + " FOR UPDATE OF a SKIP LOCKED")
                .param(id).query((rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3))).optional();
        if (row.isEmpty()) return Outcome.GONE; // moved by someone else, or locked by another node right now
        Candidate c = row.get();
        boolean published = jdbc.sql("SELECT " + PUBLISHED_ELSEWHERE + " FROM media_assets a WHERE a.id = ?")
                .param(id).query(Boolean.class).single();
        if (published) return Outcome.SKIPPED;
        String target = MediaService.PRIVATE_PREFIX + "/" + c.key().substring(PUBLIC_PREFIX.length());
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{c.key(), target});
        for (int w : widths(c.variantWidths())) pairs.add(new String[]{ImageVariants.keyFor(c.key(), w), ImageVariants.keyFor(target, w)});
        try {
            for (String[] p : pairs) {
                if (p[0].equals(c.key()) || storage.exists(p[0])) copy(p[0], p[1]);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Legacy DM image {} not moved: {}", id, e.toString());
            pairs.forEach(p -> storage.delete(p[1]));
            return Outcome.FAILED;
        }
        jdbc.sql("UPDATE media_assets SET storage_key = ? WHERE id = ?").params(target, id).update();
        afterCompletion(pairs);
        return Outcome.MOVED;
    }

    private void copy(String from, String to) throws IOException {
        StorageService.StoredObject obj = storage.get(from).orElseThrow(() -> new IOException("object " + from + " is missing"));
        try (InputStream in = obj.stream()) {
            storage.put(to, in, obj.contentType() != null ? obj.contentType() : StorageService.contentTypeFor(to));
        }
    }

    /** Commit: delete the public copies. Rollback: delete the private copies (the row still points at the old key). */
    private void afterCompletion(List<String[]> pairs) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pairs.forEach(p -> storage.delete(p[0]));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                pairs.forEach(p -> storage.delete(status == STATUS_COMMITTED ? p[0] : p[1]));
            }
        });
    }

    private static List<Integer> widths(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.strip()));
            } catch (NumberFormatException ignored) {
                // unknown entry: no variant to move
            }
        }
        return out;
    }

    public List<Run> runs(int limit) {
        return jdbc.sql("""
                        SELECT id, job, triggered_by, started_at, finished_at, candidates, moved, skipped, failed, detail
                        FROM maintenance_runs WHERE job = ? ORDER BY id DESC LIMIT ?""")
                .params(JOB, limit)
                .query((rs, i) -> new Run(rs.getLong("id"), rs.getString("job"), (Long) rs.getObject("triggered_by", Long.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(rs.getObject("finished_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null),
                        rs.getInt("candidates"), rs.getInt("moved"), rs.getInt("skipped"), rs.getInt("failed"), rs.getString("detail")))
                .list();
    }
}
