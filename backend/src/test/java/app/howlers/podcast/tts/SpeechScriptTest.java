package app.howlers.podcast.tts;

import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.podcast.tts.SpeechScript.Chunk;
import app.howlers.podcast.tts.SpeechScript.Turn;
import app.howlers.podcast.tts.TtsProvider.Speaker;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechScriptTest {

    /** The shape ScriptDrafter asks the AI model for, including the parts that must not be spoken. */
    static final String SCRIPT = """
            # Rust vs Go: what c/programming actually thinks
            Members compare the two languages for backend services, and the discussion is thinner than you'd hope [2].

            ## Outline
            1. Intro (≈00:00)
            2. Performance (≈01:30)

            ## Intro (≈00:00)
            **HOST A:** Welcome to the show. Today we're reading what [c/programming](https://howlers.local/c/programming) said [1].
            **HOST B:** And there's *a lot* of disagreement [1][3].

            ## Performance (≈01:30)
            **HOST A:** As u/mira_k put it, "the borrow checker pays rent" [2].
            continuing the same turn on the next line.

            > **Note:** this draft hit the length limit and ends early.

            HOST B: Plain speaker lines work too. See https://example.com/benchmarks for numbers.

            ## Empty section with no dialogue

            ---

            ## Sources

            [1] Rust or Go? (c/programming, u/mira_k) /c/programming/p/1
            **HOST A:** this is inside the sources and must never be read.
            """;

    @Test
    void splitsSpeakerTurnsAndSkipsEverythingElse() {
        SpeechScript.Parsed p = SpeechScript.parse(SCRIPT);
        assertThat(p.turns()).extracting(Turn::speaker).containsExactly(Speaker.A, Speaker.B, Speaker.A, Speaker.B);
        assertThat(p.turns().get(0).text()).isEqualTo("Welcome to the show. Today we're reading what c/programming said.");
        assertThat(p.turns().get(1).text()).isEqualTo("And there's a lot of disagreement.");
        assertThat(p.turns().get(2).text()).isEqualTo("As u/mira_k put it, \"the borrow checker pays rent\". continuing the same turn on the next line.");
        assertThat(p.turns().get(3).text()).isEqualTo("Plain speaker lines work too. See for numbers.");
        assertThat(String.join(" ", p.turns().stream().map(Turn::text).toList()))
                .doesNotContain("[1]").doesNotContain("**").doesNotContain("http").doesNotContain("Note").doesNotContain("sources and must");
    }

    @Test
    void sectionsWithDialogueBecomeChapters() {
        SpeechScript.Parsed p = SpeechScript.parse(SCRIPT);
        assertThat(p.sections()).extracting(SpeechScript.Section::title).containsExactly("Intro", "Performance");
        assertThat(p.turns()).extracting(Turn::section).containsExactly(0, 0, 1, 1);
    }

    @Test
    void scriptsWithoutHeadingsOrHostsAreHandled() {
        SpeechScript.Parsed loose = SpeechScript.parse("HOST A: Hi.\nhost b: Hello there.\n**HOST A**: Bye.");
        assertThat(loose.turns()).extracting(Turn::speaker).containsExactly(Speaker.A, Speaker.B, Speaker.A);
        assertThat(loose.turns()).extracting(Turn::section).containsOnly(-1);
        assertThat(loose.sections()).isEmpty();
        assertThat(SpeechScript.parse("# Just a title\nSome notes with no hosts.").turns()).isEmpty();
        assertThat(SpeechScript.parse(null).characters()).isZero();
        assertThat(SpeechScript.parse("**HOST A:** [1]").turns()).as("a turn with nothing to say is dropped").isEmpty();
    }

    @Test
    void shortTurnsAreOneChunkEach() {
        List<Chunk> chunks = SpeechScript.chunk(SpeechScript.parse(SCRIPT).turns(), 2_500);
        assertThat(chunks).hasSize(4).allMatch(Chunk::firstOfTurn);
        assertThat(chunks).extracting(Chunk::turn).containsExactly(0, 1, 2, 3);
    }

    @Test
    void longTurnsAreCutAtSentenceBoundariesUnderTheLimit() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 40; i++) b.append("This is sentence number ").append(i).append(" of a long monologue! ");
        String text = b.toString().strip();
        List<Chunk> chunks = SpeechScript.chunk(List.of(new Turn(Speaker.B, text, 2)), 300);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.text().length() <= 300);
        assertThat(chunks).allMatch(c -> c.text().endsWith("!"));        // never mid-sentence
        assertThat(chunks).allMatch(c -> c.speaker() == Speaker.B && c.section() == 2 && c.turn() == 0);
        assertThat(chunks.getFirst().firstOfTurn()).isTrue();
        assertThat(chunks.subList(1, chunks.size())).noneMatch(Chunk::firstOfTurn);
        assertThat(String.join(" ", chunks.stream().map(Chunk::text).toList())).isEqualTo(text); // nothing lost
    }

    @Test
    void overlongSentencesFallBackToWordsThenHardCuts() {
        String words = "word ".repeat(200).strip(); // 999 chars, no sentence end
        List<String> parts = SpeechScript.split(words, 100);
        assertThat(parts).allMatch(p -> p.length() <= 100 && !p.startsWith(" ") && !p.endsWith(" "));
        assertThat(String.join(" ", parts)).isEqualTo(words);

        String giant = "x".repeat(250);
        assertThat(SpeechScript.split(giant, 100)).containsExactly("x".repeat(100), "x".repeat(100), "x".repeat(50));
    }

    @Test
    void cleaningKeepsReadableText() {
        assertThat(SpeechScript.clean("**Bold** and _kept_under_scores_ `code` [4] ~~gone~~")).isEqualTo("Bold and _kept_under_scores_ code gone");
        assertThat(SpeechScript.clean("Read [the thread](/c/x/p/1) now [12] [13].")).isEqualTo("Read the thread now.");
    }
}
