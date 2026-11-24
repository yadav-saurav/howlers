package app.howlers.reputation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic topic-reputation math (no I/O), so the full rebuild, the incremental job and the tests all
 * share one definition. Given the same vote facts it always returns the same totals: facts are sorted by a total
 * order before caps are applied, and voter weights come from a separate unweighted pass, never from the result
 * being computed (no feedback loop).
 *
 * <p>Per vote, for each topic of the voted content:
 * <pre>
 *   delta = (up ? UP : DOWN) × trust(voter) × quality(voter's base reputation in that topic)
 *   trust   = 0 for self-votes, removed content, suspended/deleted voters
 *           = NEW_ACCOUNT_TRUST if the voter's account was younger than NEW_ACCOUNT_DAYS when voting
 *           = LOW_KARMA_TRUST if the voter has less than MIN_KARMA karma
 *           = 1 otherwise
 *   quality = 1 + min(ln(1 + base) / ln(1 + QUALITY_SATURATION), 1)        (1.0 … 2.0)
 * </pre>
 * Caps: a voter counts for at most {@link #PAIR_DAILY_CAP} votes on one author per UTC day; an author gains at most
 * {@link #TOPIC_DAILY_CAP} points per topic per UTC day (losses are never capped, so caps can't hide criticism).
 */
public final class ReputationMath {
    private ReputationMath() {}

    public static final double UP = 1.0;
    public static final double DOWN = -0.5;
    public static final int PAIR_DAILY_CAP = 3;
    public static final double TOPIC_DAILY_CAP = 25.0;
    public static final int NEW_ACCOUNT_DAYS = 7;
    public static final double NEW_ACCOUNT_TRUST = 0.25;
    public static final int MIN_KARMA = 10;
    public static final double LOW_KARMA_TRUST = 0.5;
    public static final double QUALITY_SATURATION = 200.0;

    /** One vote on one post or comment. {@code kind} is 'P' or 'C'; topics are the content's topic ids. */
    public record VoteFact(char kind, long targetId, long voterId, long authorId, int value, Instant at, boolean removed,
                           long[] topics) {}

    public record Voter(Instant createdAt, int karma, boolean active) {}

    public record Key(long userId, long topicId) {}

    public static final class Totals {
        double points;
        double base;
        int up;
        int down;
        int capped;

        public double points() { return points; }
        public double base() { return base; }
        public int up() { return up; }
        public int down() { return down; }
        public int capped() { return capped; }
    }

    @FunctionalInterface
    public interface VoterBase {
        double of(long voterId, long topicId);
    }

    public static double quality(double voterBase) {
        if (voterBase <= 0) return 1.0;
        return 1.0 + Math.min(Math.log1p(voterBase) / Math.log1p(QUALITY_SATURATION), 1.0);
    }

    public static double trust(Voter v, Instant votedAt) {
        if (v == null || !v.active()) return 0;
        if (v.createdAt() != null && Duration.between(v.createdAt(), votedAt).toDays() < NEW_ACCOUNT_DAYS) return NEW_ACCOUNT_TRUST;
        if (v.karma() < MIN_KARMA) return LOW_KARMA_TRUST;
        return 1.0;
    }

    private static final Comparator<VoteFact> ORDER = Comparator.comparing(VoteFact::at)
            .thenComparing(VoteFact::kind).thenComparingLong(VoteFact::targetId).thenComparingLong(VoteFact::voterId);

    /** Computes totals for every (author, topic) present in {@code facts}. */
    public static Map<Key, Totals> compute(List<VoteFact> facts, Map<Long, Voter> voters, VoterBase voterBase) {
        List<VoteFact> sorted = new ArrayList<>(facts);
        sorted.sort(ORDER);
        Map<Key, Totals> out = new LinkedHashMap<>();
        Map<String, Integer> pairCount = new HashMap<>();
        Map<String, double[]> dayGain = new HashMap<>(); // (author, topic, day) → {points gained, base gained}

        for (VoteFact f : sorted) {
            if (f.voterId() == f.authorId() || f.removed() || f.value() == 0 || f.topics().length == 0) continue;
            double trust = trust(voters.get(f.voterId()), f.at());
            if (trust <= 0) continue;
            LocalDate day = f.at().atZone(ZoneOffset.UTC).toLocalDate();
            int n = pairCount.merge(f.voterId() + ":" + f.authorId() + ":" + day, 1, Integer::sum);
            boolean overPairCap = n > PAIR_DAILY_CAP;
            for (long topic : f.topics()) {
                Totals t = out.computeIfAbsent(new Key(f.authorId(), topic), k -> new Totals());
                if (overPairCap) {
                    t.capped++;
                    continue;
                }
                double unit = f.value() > 0 ? UP : DOWN;
                double base = unit * trust;
                double points = base * quality(voterBase.of(f.voterId(), topic));
                if (f.value() > 0) {
                    double[] g = dayGain.computeIfAbsent(f.authorId() + ":" + topic + ":" + day, k -> new double[2]);
                    double allowedPoints = Math.max(0, Math.min(points, TOPIC_DAILY_CAP - g[0]));
                    double allowedBase = Math.max(0, Math.min(base, TOPIC_DAILY_CAP - g[1]));
                    if (allowedPoints < points) t.capped++;
                    g[0] += allowedPoints;
                    g[1] += allowedBase;
                    points = allowedPoints;
                    base = allowedBase;
                    t.up++;
                } else {
                    t.down++;
                }
                t.points += points;
                t.base += base;
            }
        }
        return out;
    }

    /** Full two-pass computation: unweighted base first, then quality weights from that base. */
    public static Map<Key, Totals> computeTwoPass(List<VoteFact> facts, Map<Long, Voter> voters) {
        Map<Key, Totals> base = compute(facts, voters, (v, t) -> 0);
        return compute(facts, voters, (v, t) -> {
            Totals b = base.get(new Key(v, t));
            return b == null ? 0 : Math.max(0, b.base);
        });
    }

    // ------------------------------------------------------------------ tiers

    public enum Tier {
        NONE("", 0), CONTRIBUTOR("Contributor", 5), TRUSTED("Trusted", 25), EXPERT("Expert", 100), AUTHORITY("Authority", 400);

        public final String label;
        public final double min;

        Tier(String label, double min) {
            this.label = label;
            this.min = min;
        }

        public static Tier of(double points) {
            Tier t = NONE;
            for (Tier x : values()) if (points >= x.min) t = x;
            return t;
        }
    }

    /** "Top contributor" = top {@link #TOP_RANK} in a topic with at least {@link #TOP_MIN_POINTS} points. */
    public static final int TOP_RANK = 3;
    public static final double TOP_MIN_POINTS = 10;

    /** The expertise chip in threads: Trusted or above, or a top contributor in that topic. */
    public static boolean showsExpertise(double points, boolean topContributor) {
        return Tier.of(points).ordinal() >= Tier.TRUSTED.ordinal() || topContributor;
    }
}
