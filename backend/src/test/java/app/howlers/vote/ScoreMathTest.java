package app.howlers.vote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScoreMathTest {

    @Test
    void wilsonPrefersConsistentApprovalOverSmallSamples() {
        assertThat(ScoreMath.wilson(0, 0)).isZero();
        // 1 of 1 is "100%" but should rank below 90 of 100.
        assertThat(ScoreMath.wilson(1, 0)).isLessThan(ScoreMath.wilson(90, 10));
        assertThat(ScoreMath.wilson(90, 10)).isGreaterThan(ScoreMath.wilson(60, 40));
    }

    @Test
    void hotRewardsRatioNotJustVolume() {
        Instant t = Instant.parse("2026-11-01T12:00:00Z");
        double divisive = ScoreMath.hot(120, 100, 0, t);    // net 20, split
        double wellLiked = ScoreMath.hot(22, 2, 0, t);       // net 20, clear approval
        assertThat(wellLiked).isGreaterThan(divisive);
    }

    @Test
    void hotDecaysWithAge() {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        Instant older = now.minus(Duration.ofSeconds((long) ScoreMath.DECAY_SECONDS));
        // A post one decay period older needs ~10x the net score to tie (within the small Wilson term).
        assertThat(Math.abs(ScoreMath.hot(100, 0, 0, older) - ScoreMath.hot(10, 0, 0, now))).isLessThan(0.3);
        assertThat(ScoreMath.hot(5, 0, 0, now)).isGreaterThan(ScoreMath.hot(5, 0, 0, older));
    }

    @Test
    void commentFarmingBarelyMovesRank() {
        Instant t = Instant.parse("2026-11-01T12:00:00Z");
        double delta = ScoreMath.hot(10, 0, 1000, t) - ScoreMath.hot(10, 0, 0, t);
        assertThat(delta).isLessThan(1.1); // 1000 comments < one order of magnitude of votes
    }

    @Test
    void controversyNeedsBothSides() {
        assertThat(ScoreMath.controversy(50, 0)).isZero();
        assertThat(ScoreMath.controversy(50, 48)).isGreaterThan(ScoreMath.controversy(90, 8));
    }

    @Test
    void milestonesFireOnceWhenCrossed() {
        assertThat(ScoreMath.crossedMilestone(9, 10)).isEqualTo(10);
        assertThat(ScoreMath.crossedMilestone(10, 11)).isEqualTo(-1);
        assertThat(ScoreMath.crossedMilestone(11, 9)).isEqualTo(-1);
    }
}
