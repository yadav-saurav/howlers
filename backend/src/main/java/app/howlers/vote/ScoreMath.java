package app.howlers.vote;

import java.time.Instant;

/**
 * Howlers' ranking maths. Documented in docs/RANKING.md — keep the two in sync.
 *
 * <p>Design goals: reward <em>approval ratio</em> over raw volume (Wilson bound), give discussion a small,
 * logarithmic boost equal to a vote's, and decay with age so the front page turns over.
 */
public final class ScoreMath {
    private ScoreMath() {}

    /** z for an 80% one-sided confidence interval: conservative enough to resist tiny-sample flukes. */
    private static final double Z = 1.281551565545;
    /** Every 14 hours of age is worth one order of magnitude of net score. */
    public static final double DECAY_SECONDS = 50_400d;
    /** Epoch offset keeps hot_rank values small and readable. */
    private static final long EPOCH = 1_700_000_000L;

    /** Lower bound of the Wilson score interval for the share of upvotes. 0 when there are no votes. */
    public static double wilson(int up, int down) {
        int n = up + down;
        if (n <= 0) return 0;
        double p = (double) up / n;
        double z2 = Z * Z;
        return (p + z2 / (2 * n) - Z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n);
    }

    /** Engagement that drives Hot, Top and Rising: every upvote and every comment counts one, a downvote takes one off. */
    public static int engagement(int up, int down, int comments) {
        return up - down + Math.max(comments, 0);
    }

    /**
     * hot = sign(e)·log10(max(|e|,1)) + 1.5·wilson + age/DECAY, where e = upvotes − downvotes + comments.
     * Upvotes and comments weigh the same, so the posts people vote on and talk about rise; the log means
     * the 10th point matters as much as the next 90; the Wilson term lets a well-received small post beat
     * a large divisive one; and age decay keeps the front page turning over.
     */
    public static double hot(int up, int down, int comments, Instant createdAt) {
        int e = engagement(up, down, comments);
        double order = Math.log10(Math.max(Math.abs(e), 1)) * Math.signum(e);
        double quality = 1.5 * wilson(up, down);
        double age = (createdAt.getEpochSecond() - EPOCH) / DECAY_SECONDS;
        return order + quality + age;
    }

    /** Large and evenly split = controversial. 0 when one side is empty. */
    public static double controversy(int up, int down) {
        if (up <= 0 || down <= 0) return 0;
        double magnitude = up + down;
        double balance = up > down ? (double) down / up : (double) up / down;
        return Math.pow(magnitude, balance);
    }

    private static final int[] MILESTONES = {10, 50, 100, 500, 1000, 5000};

    /** The milestone crossed when going from {@code before} to {@code after}, or -1. */
    public static int crossedMilestone(int before, int after) {
        for (int m : MILESTONES) {
            if (before < m && after >= m) return m;
        }
        return -1;
    }
}
