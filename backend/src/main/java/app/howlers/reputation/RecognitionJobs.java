package app.howlers.reputation;

import app.howlers.recognition.RecognitionService;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work for topic reputation and moderator recognition.
 * <ul>
 *   <li>At startup (after the demo seed has run, since ApplicationReadyEvent follows every ApplicationRunner): a full
 *       reputation rebuild and this month's and last month's moderator scores, so pages reflect the existing rows.</li>
 *   <li>Every few seconds: recompute authors whose content received votes (the vote only queued them).</li>
 *   <li>Every 15 seconds: refresh moderator scores when any moderation data changed (removals, bans, reports,
 *       appeals, endorsements, moderator roles, site-admin actions), so the board is live; plus a full refresh every
 *       10 minutes.</li>
 *   <li>Nightly: full deterministic reputation rebuild (refreshes the voter weights the incremental job reuses).</li>
 * </ul>
 */
@Component
public class RecognitionJobs {
    private static final Logger log = LoggerFactory.getLogger(RecognitionJobs.class);

    private final ReputationService reputation;
    private final RecognitionService recognition;
    private final JdbcClient jdbc;
    /** Fingerprint of the moderation tables at the last refresh; a change triggers a recompute. */
    private volatile String lastModFingerprint = "";

    /** Cheap summary of every table the moderator score reads: any new or resolved row changes it. */
    static final String MOD_FINGERPRINT_SQL = """
            SELECT concat_ws('|',
              (SELECT count(*) || ':' || coalesce(max(id), 0) || ':' || coalesce(max(reversed_at)::text, '') FROM moderation_actions),
              (SELECT count(*) || ':' || coalesce(max(resolved_at)::text, '') FROM reports),
              (SELECT count(*) || ':' || coalesce(max(resolved_at)::text, '') FROM appeals),
              (SELECT count(*) || ':' || coalesce(max(id), 0) FROM moderator_endorsements),
              (SELECT count(*) FROM community_members WHERE role IN ('OWNER', 'ADMIN', 'MODERATOR')),
              (SELECT count(*) || ':' || coalesce(max(id), 0) FROM admin_actions))
            """;

    public RecognitionJobs(ReputationService reputation, RecognitionService recognition, JdbcClient jdbc) {
        this.reputation = reputation;
        this.recognition = recognition;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            var r = reputation.rebuildAll();
            int mods = refreshModeratorScores();
            log.info("Recomputed topic reputation ({} authors, {} rows) and {} moderator score rows", r.authors(), r.rows(), mods);
        } catch (RuntimeException e) {
            log.warn("Startup recompute failed; the scheduled jobs will retry", e);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void processDirty() {
        try {
            reputation.processDirty(500);
        } catch (RuntimeException e) {
            log.warn("Incremental reputation recompute failed", e);
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    public void nightly() {
        reputation.rebuildAll();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void liveModeratorScores() {
        try {
            String now = jdbc.sql(MOD_FINGERPRINT_SQL).query(String.class).single();
            if (!now.equals(lastModFingerprint)) {
                refreshModeratorScores();
                lastModFingerprint = now;
            }
        } catch (RuntimeException e) {
            log.warn("Live moderator score refresh failed", e);
        }
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void scheduledModeratorScores() {
        try {
            refreshModeratorScores();
        } catch (RuntimeException e) {
            log.warn("Moderator score refresh failed", e);
        }
    }

    public int refreshModeratorScores() {
        YearMonth now = RecognitionService.currentPeriod();
        return recognition.recompute(now, 0) + recognition.recompute(now.minusMonths(1), 0);
    }
}
