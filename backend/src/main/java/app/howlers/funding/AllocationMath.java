package app.howlers.funding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure allocation arithmetic over integer minor units. Every split hands out exactly {@code total}: the integer
 * remainder goes to the largest fractional remainders (ties broken by the lower moderator id), so nothing is lost
 * to rounding and the ledger always reconciles.
 */
public final class AllocationMath {
    private AllocationMath() {}

    /** Points for each appeal a moderator handled; appeals take more care than a report decision. */
    public static final int APPEAL_POINTS = 2;

    /**
     * Activity score. Only work that answers someone else counts: report targets resolved (each distinct post or
     * comment once, whether removed or approved, and never reports the moderator filed themselves) and appeals
     * handled. Removals on their own earn nothing. Each component is capped so volume alone can't dominate.
     */
    public static int activityScore(int reportsResolved, int appealsHandled, int cap) {
        long reports = Math.min(Math.max(reportsResolved, 0), cap);
        long appeals = Math.min((long) Math.max(appealsHandled, 0) * APPEAL_POINTS, cap);
        return (int) (reports + appeals);
    }

    /** Equal split among {@code ids} (in the given order of ids, remainder to the lowest ids). */
    public static Map<Long, Long> equal(long total, List<Long> ids) {
        Map<Long, Long> weights = new LinkedHashMap<>();
        ids.forEach(id -> weights.put(id, 1L));
        return weighted(total, weights);
    }

    /** Proportional split by non-negative integer weights; largest-remainder rounding. All-zero weights → empty map. */
    public static Map<Long, Long> weighted(long total, Map<Long, Long> weights) {
        if (total < 0) throw new IllegalArgumentException("total must be >= 0");
        long sum = 0;
        for (long w : weights.values()) {
            if (w < 0) throw new IllegalArgumentException("weights must be >= 0");
            sum = Math.addExact(sum, w);
        }
        Map<Long, Long> out = new LinkedHashMap<>();
        if (sum == 0) return out;
        record Part(long id, long base, long remainder) {}
        List<Part> parts = new ArrayList<>();
        long handed = 0;
        for (var e : weights.entrySet()) {
            // total and weights are bounded (goal ≤ 1e9 major units ⇒ ≤ 1e11 minor; weights ≤ a few thousand), so this can't overflow,
            // but multiplyExact makes sure we'd fail loudly rather than corrupt an amount.
            long product = Math.multiplyExact(total, e.getValue());
            long base = product / sum;
            parts.add(new Part(e.getKey(), base, product % sum));
            handed += base;
        }
        long left = total - handed;
        List<Part> byRemainder = new ArrayList<>(parts);
        byRemainder.sort(Comparator.comparingLong(Part::remainder).reversed().thenComparingLong(Part::id));
        Map<Long, Long> extra = new LinkedHashMap<>();
        for (int i = 0; i < left; i++) extra.merge(byRemainder.get(i % byRemainder.size()).id(), 1L, Long::sum);
        for (Part p : parts) out.put(p.id(), p.base() + extra.getOrDefault(p.id(), 0L));
        return out;
    }
}
