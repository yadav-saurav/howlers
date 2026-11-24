package app.howlers.recognition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.howlers.recognition.ModeratorScoreMath.Inputs;
import app.howlers.recognition.ModeratorScoreMath.Result;
import org.junit.jupiter.api.Test;

class ModeratorScoreMathTest {

    private static Inputs in(int decisions, int reversed, int handled, Double median, int upheld, int overturned, int endorsements,
                             int coMods) {
        return new Inputs(decisions, reversed, handled, median, upheld, overturned, endorsements, coMods, 10, 9, 0.02, 0.04);
    }

    @Test
    void removalVolumeNeverRaisesTheScore() {
        Result few = ModeratorScoreMath.score(in(5, 0, 5, 60.0, 0, 0, 1, 2));
        Result many = ModeratorScoreMath.score(in(500, 0, 5, 60.0, 0, 0, 1, 2));
        assertThat(many.score()).isEqualTo(few.score());
    }

    @Test
    void overturnedDecisionsLowerConsistencyAndScore() {
        Result clean = ModeratorScoreMath.score(in(4, 0, 4, 30.0, 1, 0, 0, 1));
        Result reversed = ModeratorScoreMath.score(in(4, 1, 4, 30.0, 0, 1, 0, 1));
        assertThat(reversed.consistency()).isEqualTo(0.75);
        assertThat(reversed.appealQuality()).isZero();
        assertThat(reversed.score()).isLessThan(clean.score());
    }

    @Test
    void missingComponentsDropOutInsteadOfCountingAsZero() {
        Result r = ModeratorScoreMath.score(new Inputs(3, 0, 0, null, 0, 0, 0, 0, 0, 0, null, null));
        assertThat(r.consistency()).isEqualTo(1.0);
        assertThat(r.responsiveness()).isNull();
        assertThat(r.appealQuality()).isNull();
        assertThat(r.health()).isNull();
        assertThat(r.peer()).isNull();
        assertThat(r.score()).isEqualTo(100.0);
    }

    @Test
    void responseTimeHalvesAtTwelveHoursAndHealthIsNormalised() {
        Result r = ModeratorScoreMath.score(in(1, 0, 3, 12 * 60.0, 0, 0, 0, 0));
        assertThat(r.responsiveness()).isCloseTo(0.5, within(1e-9));
        // 9/10 reports resolved, removal rate halved vs last period → trend 0.75.
        assertThat(r.health()).isCloseTo(0.7 * 0.9 + 0.3 * 0.75, within(1e-9));
    }

    @Test
    void badgesFollowTheirRules() {
        Result r = ModeratorScoreMath.score(in(6, 0, 4, 45.0, 2, 0, 2, 3));
        assertThat(r.badges()).contains("FAST_RESPONDER", "CONSISTENT", "UPHELD_ON_APPEAL", "PEER_ENDORSED", "CLEAR_QUEUE");
        Result slow = ModeratorScoreMath.score(in(6, 1, 4, 600.0, 1, 1, 0, 3));
        assertThat(slow.badges()).doesNotContain("FAST_RESPONDER", "CONSISTENT", "UPHELD_ON_APPEAL", "PEER_ENDORSED");
    }
}
