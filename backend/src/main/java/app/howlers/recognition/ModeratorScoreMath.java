package app.howlers.recognition;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure moderator-score math. Every component is a 0–1 quality ratio; none of them grows with the number of
 * removals, so removing more content never raises a score. Missing components (e.g. no appeals this month) drop
 * out and the remaining weights are renormalised, rather than being treated as 0 or 1.
 *
 * <pre>
 *   consistency    = 1 − reversed / decisions             decisions = removals + bans made in the period;
 *                                                          reversed  = overturned on appeal or undone by another moderator
 *   responsiveness = 1 / (1 + medianResponseHours / 12)   median time from report to resolution (12 h → 0.5)
 *   appealQuality  = upheld / (upheld + overturned)       appeals on this moderator's actions decided in the period
 *   health         = 0.7 × resolvedShare + 0.3 × trend     community-level: share of the period's reports resolved, and
 *                                                          whether removals per new post/comment fell versus last period
 *   peer           = min(1, endorsements / min(3, coModerators))
 *   score          = 100 × Σ wᵢ·cᵢ / Σ wᵢ over the components that exist
 * </pre>
 */
public final class ModeratorScoreMath {
    private ModeratorScoreMath() {}

    public static final double W_CONSISTENCY = 0.30;
    public static final double W_RESPONSIVENESS = 0.20;
    public static final double W_APPEALS = 0.20;
    public static final double W_HEALTH = 0.15;
    public static final double W_PEER = 0.15;
    public static final double RESPONSE_HALF_HOURS = 12;
    public static final int PEER_FULL_AT = 3;
    /** Decisions + reports handled needed to appear on a leaderboard. A gate, never a ranking input. */
    public static final int MIN_ACTIVITY = 3;

    public record Inputs(int decisions, int reversed, int reportsHandled, Double medianResponseMinutes, int appealsUpheld,
                         int appealsOverturned, int endorsements, int coModerators, int communityReports,
                         int communityReportsResolved, Double removalRate, Double prevRemovalRate) {}

    public record Result(Double consistency, Double responsiveness, Double appealQuality, Double health, Double peer,
                         double score, int activity, List<String> badges) {}

    public enum Badge {
        FAST_RESPONDER("Fast responder", "Median response under 2 hours across at least 3 handled reports"),
        CONSISTENT("Consistent", "At least 5 removals or bans in the period and none overturned"),
        UPHELD_ON_APPEAL("Upheld on appeal", "At least 2 appeals decided and every one upheld"),
        PEER_ENDORSED("Peer endorsed", "Endorsed as helpful by at least 2 co-moderators"),
        CLEAR_QUEUE("Clear queue", "Community resolved at least 90% of 5 or more reports, with this moderator handling some");

        public final String label;
        public final String rule;

        Badge(String label, String rule) {
            this.label = label;
            this.rule = rule;
        }
    }

    public static Result score(Inputs in) {
        Double consistency = in.decisions() > 0 ? clamp(1.0 - (double) in.reversed() / in.decisions()) : null;
        Double responsiveness = in.medianResponseMinutes() != null && in.reportsHandled() > 0
                ? 1.0 / (1.0 + (in.medianResponseMinutes() / 60.0) / RESPONSE_HALF_HOURS) : null;
        int appeals = in.appealsUpheld() + in.appealsOverturned();
        Double appealQuality = appeals > 0 ? (double) in.appealsUpheld() / appeals : null;
        Double health = health(in);
        Double peer = in.coModerators() > 0
                ? Math.min(1.0, (double) in.endorsements() / Math.min(PEER_FULL_AT, in.coModerators())) : null;

        double num = 0, den = 0;
        double[][] parts = {
                {W_CONSISTENCY, consistency == null ? Double.NaN : consistency},
                {W_RESPONSIVENESS, responsiveness == null ? Double.NaN : responsiveness},
                {W_APPEALS, appealQuality == null ? Double.NaN : appealQuality},
                {W_HEALTH, health == null ? Double.NaN : health},
                {W_PEER, peer == null ? Double.NaN : peer},
        };
        for (double[] p : parts) {
            if (Double.isNaN(p[1])) continue;
            num += p[0] * p[1];
            den += p[0];
        }
        double score = den == 0 ? 0 : Math.round(1000.0 * num / den) / 10.0;
        int activity = in.decisions() + in.reportsHandled();

        List<String> badges = new ArrayList<>();
        if (in.reportsHandled() >= 3 && in.medianResponseMinutes() != null && in.medianResponseMinutes() <= 120) {
            badges.add(Badge.FAST_RESPONDER.name());
        }
        if (in.decisions() >= 5 && in.reversed() == 0) badges.add(Badge.CONSISTENT.name());
        if (appeals >= 2 && in.appealsOverturned() == 0) badges.add(Badge.UPHELD_ON_APPEAL.name());
        if (in.endorsements() >= 2) badges.add(Badge.PEER_ENDORSED.name());
        if (in.communityReports() >= 5 && in.reportsHandled() > 0
                && (double) in.communityReportsResolved() / in.communityReports() >= 0.9) {
            badges.add(Badge.CLEAR_QUEUE.name());
        }
        return new Result(consistency, responsiveness, appealQuality, health, peer, score, activity, badges);
    }

    static Double health(Inputs in) {
        Double resolvedShare = in.communityReports() > 0 ? (double) in.communityReportsResolved() / in.communityReports() : null;
        Double trend = null;
        if (in.removalRate() != null && in.prevRemovalRate() != null) {
            double max = Math.max(in.removalRate(), in.prevRemovalRate());
            if (max > 0) trend = clamp(0.5 + (in.prevRemovalRate() - in.removalRate()) / (2 * max));
        }
        if (resolvedShare != null && trend != null) return 0.7 * resolvedShare + 0.3 * trend;
        return resolvedShare != null ? resolvedShare : trend;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
