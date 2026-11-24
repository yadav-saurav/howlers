package app.howlers.vote;

import java.time.Instant;

/**
 * Howlers' ranking maths. Documented in docs/RANKING.md — keep the two in sync.
 *
 * <p>Design goals: reward <em>approval ratio</em> over raw volume (Wilson bound), give discussion a small,
 * logarithmic (so un-farmable) boost, and decay with age so the front page turns over.
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

    /**
     * hot = sign(net)·log10(max(|net|,1)) + 1.5·wilson + 0.35·log10(1 + comments) + age/DECAY.
     * The log on net score means the 10th vote matters as much as the next 90; the Wilson term lets a
     * well-received small post beat a large divisive one; comments are capped by the log so reply
     * farming barely moves the needle.
     */
    public static double hot(int up, int down, int comments, Instant createdAt) {
        int net = up - down;
        double order = Math.log10(Math.max(Math.abs(net), 1)) * Math.signum(net);
        double quality = 1.5 * wilson(up, down);
        double discussion = 0.35 * Math.log10(1 + Math.max(comments, 0));
        double age = (createdAt.getEpochSecond() - EPOCH) / DECAY_SECONDS;
        return order + quality + discussion + age;
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
