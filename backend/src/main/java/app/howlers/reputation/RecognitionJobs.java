package app.howlers.reputation;

import app.howlers.recognition.RecognitionService;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work for topic reputation and moderator recognition.
 * <ul>
 *   <li>At startup (after the demo seed has run, since ApplicationReadyEvent follows every ApplicationRunner): a full
 *       reputation rebuild and this month's and last month's moderator scores, so pages reflect the existing rows.</li>
 *   <li>Every few seconds: recompute authors whose content received votes (the vote only queued them).</li>
 *   <li>Every 10 minutes: refresh moderator scores for the current and previous month.</li>
 *   <li>Nightly: full deterministic reputation rebuild (refreshes the voter weights the incremental job reuses).</li>
 * </ul>
 */
@Component
public class RecognitionJobs {
    private static final Logger log = LoggerFactory.getLogger(RecognitionJobs.class);

    private final ReputationService reputation;
    private final RecognitionService recognition;

    public RecognitionJobs(ReputationService reputation, RecognitionService recognition) {
        this.reputation = reputation;
        this.recognition = recognition;
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
