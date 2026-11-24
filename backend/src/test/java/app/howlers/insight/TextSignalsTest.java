package app.howlers.insight;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSignalsTest {

    @Test
    void suggestsStancesFromRealisticReplies() {
        assertThat(TextSignals.suggestStance("The onboarding number matches ours almost exactly. Weeks 3-5 are the wall."))
                .isEqualTo(Stance.SUPPORT);
        assertThat(TextSignals.suggestStance("Counterpoint: some people need the motivation of a cert milestone."))
                .isEqualTo(Stance.OPPOSE);
        assertThat(TextSignals.suggestStance("Did you measure the GC issue or just feel it? We saw something similar."))
                .isEqualTo(Stance.QUESTION);
        assertThat(TextSignals.suggestStance("Measured: reducing allocations got us 70% of the way before we rewrote anything."))
                .isEqualTo(Stance.EVIDENCE);
        assertThat(TextSignals.suggestStance("Ran exactly this for 14 months and kept it up."))
                .isIn(Stance.EVIDENCE, Stance.EXPERIENCE);
        assertThat(TextSignals.suggestStance("See [the survey](https://example.com/s) for details."))
                .isEqualTo(Stance.EVIDENCE);
    }

    @Test
    void splitsSentencesWithoutBreakingCodeOrMentions() {
        var s = TextSignals.sentences("@first_job_fern the trick that helped: write it with `.clone()` everywhere first. Then remove clones one at a time.");
        assertThat(s).containsExactly(
                "@first_job_fern the trick that helped: write it with .clone() everywhere first.",
                "Then remove clones one at a time.");
    }

    @Test
    void classifiesSentenceKinds() {
        assertThat(TextSignals.kind("p99 spikes lined up with GC cycles in the traces.")).isEqualTo("DATA");
        assertThat(TextSignals.kind("We shipped it last year and it worked.")).isEqualTo("ANECDOTE");
        assertThat(TextSignals.kind("It will probably get easier.")).isEqualTo("SPECULATION");
        assertThat(TextSignals.kind("I think Go is better for this.")).isEqualTo("OPINION");
    }
}
