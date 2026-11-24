package app.howlers.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.howlers.reputation.ReputationMath.Key;
import app.howlers.reputation.ReputationMath.Tier;
import app.howlers.reputation.ReputationMath.Totals;
import app.howlers.reputation.ReputationMath.VoteFact;
import app.howlers.reputation.ReputationMath.Voter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReputationMathTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Voter VETERAN = new Voter(NOW.minus(Duration.ofDays(90)), 500, true);
    private static final long TOPIC = 7;

    private static VoteFact up(long target, long voter, long author, Instant at) {
        return new VoteFact('P', target, voter, author, 1, at, false, new long[]{TOPIC});
    }

    private static Map<Long, Voter> voters(long... ids) {
        Map<Long, Voter> m = new HashMap<>();
        for (long id : ids) m.put(id, VETERAN);
        return m;
    }

    private static double points(Map<Key, Totals> t, long author) {
        Totals x = t.get(new Key(author, TOPIC));
        return x == null ? 0 : x.points();
    }

    @Test
    void qualityWeightIsBoundedBetweenOneAndTwo() {
        assertThat(ReputationMath.quality(0)).isEqualTo(1.0);
        assertThat(ReputationMath.quality(10)).isBetween(1.0, 2.0);
        assertThat(ReputationMath.quality(ReputationMath.QUALITY_SATURATION)).isCloseTo(2.0, within(1e-9));
        assertThat(ReputationMath.quality(1_000_000)).isEqualTo(2.0);
    }

    @Test
    void upvotesFromTopicExpertsCountMoreAndDownvotesLessThanUpvotes() {
        List<VoteFact> facts = List.of(up(1, 10, 1, NOW), up(2, 11, 2, NOW));
        Map<Key, Totals> t = ReputationMath.compute(facts, voters(10, 11), (v, topic) -> v == 11 ? 50 : 0);
        assertThat(points(t, 1)).isEqualTo(1.0);
        assertThat(points(t, 2)).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);

        VoteFact down = new VoteFact('P', 3, 10, 3, -1, NOW, false, new long[]{TOPIC});
        Map<Key, Totals> d = ReputationMath.compute(List.of(down), voters(10), (v, topic) -> 0);
        assertThat(points(d, 3)).isEqualTo(ReputationMath.DOWN);
        assertThat(Math.abs(ReputationMath.DOWN)).isLessThan(ReputationMath.UP);
    }

    @Test
    void selfVotesRemovedContentAndInactiveVotersNeverCount() {
        Map<Long, Voter> v = voters(1, 10);
        v.put(11L, new Voter(NOW.minus(Duration.ofDays(90)), 500, false));
        List<VoteFact> facts = List.of(
                up(1, 1, 1, NOW),                                                   // self-vote
                new VoteFact('C', 2, 10, 1, 1, NOW, true, new long[]{TOPIC}),       // removed content
                up(3, 11, 1, NOW));                                                 // suspended voter
        assertThat(points(ReputationMath.compute(facts, v, (a, b) -> 0), 1)).isZero();
    }

    @Test
    void youngAndLowKarmaAccountsCountLess() {
        Map<Long, Voter> v = new HashMap<>();
        v.put(10L, new Voter(NOW.minus(Duration.ofDays(2)), 500, true));
        v.put(11L, new Voter(NOW.minus(Duration.ofDays(90)), 3, true));
        assertThat(points(ReputationMath.compute(List.of(up(1, 10, 1, NOW)), v, (a, b) -> 0), 1)).isEqualTo(ReputationMath.NEW_ACCOUNT_TRUST);
        assertThat(points(ReputationMath.compute(List.of(up(1, 11, 1, NOW)), v, (a, b) -> 0), 1)).isEqualTo(ReputationMath.LOW_KARMA_TRUST);
    }

    @Test
    void pairCapLimitsOneVoterPerAuthorPerDay() {
        List<VoteFact> facts = new ArrayList<>();
        for (int i = 0; i < 6; i++) facts.add(up(100 + i, 10, 1, NOW.plusSeconds(i)));
        facts.add(up(200, 10, 1, NOW.plus(Duration.ofDays(1)))); // a new UTC day resets the pair cap
        Map<Key, Totals> t = ReputationMath.compute(facts, voters(10), (a, b) -> 0);
        assertThat(points(t, 1)).isEqualTo(ReputationMath.PAIR_DAILY_CAP + 1.0);
        assertThat(t.get(new Key(1, TOPIC)).capped()).isEqualTo(3);
    }

    @Test
    void topicDailyCapLimitsGainsButNeverLosses() {
        List<VoteFact> facts = new ArrayList<>();
        Map<Long, Voter> v = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            facts.add(up(100 + i, 1000 + i, 1, NOW.plusSeconds(i)));
            v.put(1000L + i, VETERAN);
        }
        for (int i = 0; i < 4; i++) {
            facts.add(new VoteFact('P', 300 + i, 2000 + i, 1, -1, NOW.plusSeconds(100 + i), false, new long[]{TOPIC}));
            v.put(2000L + i, VETERAN);
        }
        Map<Key, Totals> t = ReputationMath.compute(facts, v, (a, b) -> 0);
        assertThat(points(t, 1)).isEqualTo(ReputationMath.TOPIC_DAILY_CAP + 4 * ReputationMath.DOWN);
    }

    @Test
    void resultIsIndependentOfInputOrder() {
        List<VoteFact> facts = new ArrayList<>();
        Map<Long, Voter> v = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            facts.add(up(i % 7, 10 + i % 5, 1 + i % 3, NOW.plusSeconds(i * 3600L)));
            v.put(10L + i % 5, VETERAN);
        }
        Map<Key, Totals> a = ReputationMath.computeTwoPass(facts, v);
        Collections.reverse(facts);
        Map<Key, Totals> b = ReputationMath.computeTwoPass(facts, v);
        for (Key k : a.keySet()) assertThat(b.get(k).points()).isEqualTo(a.get(k).points());
    }

    @Test
    void tiersAndExpertiseChip() {
        assertThat(Tier.of(0)).isEqualTo(Tier.NONE);
        assertThat(Tier.of(5)).isEqualTo(Tier.CONTRIBUTOR);
        assertThat(Tier.of(99.9)).isEqualTo(Tier.TRUSTED);
        assertThat(Tier.of(400)).isEqualTo(Tier.AUTHORITY);
        assertThat(ReputationMath.showsExpertise(30, false)).isTrue();
        assertThat(ReputationMath.showsExpertise(12, true)).isTrue();
        assertThat(ReputationMath.showsExpertise(12, false)).isFalse();
    }
}
