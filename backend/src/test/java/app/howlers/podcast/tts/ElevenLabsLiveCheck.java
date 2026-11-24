package app.howlers.podcast.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.howlers.podcast.tts.TtsProvider.Speaker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live check against the real ElevenLabs API: one short sentence per host (under 200 characters in total).
 * Excluded from the normal suite (tag {@code live}, and the class name matches neither *Test nor *IT). Run it with:
 * <pre>./mvnw.cmd test -Dsurefire.excludedGroups=none -Dtest=ElevenLabsLiveCheck -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * The key comes from {@code ELEVENLABS_API_KEY} in the environment or in {@code backend/.env.local}
 * (or the file named by {@code -Dtts.envFile=...}). The key is never printed.
 */
@Tag("live")
class ElevenLabsLiveCheck {

    static String key() throws IOException {
        String env = System.getenv("ELEVENLABS_API_KEY");
        if (env != null && !env.isBlank()) return env.strip();
        Path file = Path.of(System.getProperty("tts.envFile", ".env.local"));
        if (!Files.exists(file)) return null;
        for (String line : Files.readAllLines(file)) {
            String l = line.strip();
            if (l.startsWith("ELEVENLABS_API_KEY=")) {
                String v = l.substring("ELEVENLABS_API_KEY=".length()).strip();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    @Test
    void synthesisesOneSentencePerHost() throws Exception {
        String key = key();
        assumeTrue(key != null, "ELEVENLABS_API_KEY not set; live check skipped");
        TtsProperties props = new TtsProperties("elevenlabs", null, null, null, null,
                new TtsProperties.ElevenLabs(key, null, System.getenv("ELEVENLABS_MODEL"), null, null, null, null, null, Duration.ofSeconds(60)));
        TtsProvider p = TtsConfig.select(props);
        Map<Speaker, String> lines = Map.of(
                Speaker.A, "Welcome to Howlers, where the pack talks it through.",
                Speaker.B, "Today we read what members actually said, sources and all.");
        assertThat(lines.values().stream().mapToInt(String::length).sum()).isLessThan(200);

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        Mp3.Writer w = new Mp3.Writer(joined);
        for (Speaker s : new Speaker[] {Speaker.A, Speaker.B}) {
            long t0 = System.nanoTime();
            byte[] audio = p.synthesize(lines.get(s), s);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Mp3.Parsed parsed = Mp3.parse(audio);
            System.out.printf("LIVE %s voice=%s (%s) model=%s: %d bytes, %.2f s audio, %d frames @ %d Hz %d kbps, latency %d ms%n",
                    s, p.voice(s).voiceId(), p.voice(s).name(), p.model(), audio.length, parsed.seconds(), parsed.frameCount(),
                    parsed.first().sampleRate(), parsed.first().bitrateKbps(), ms);
            assertThat(parsed.seconds()).isGreaterThan(0.5);
            if (s == Speaker.B) w.silence(350);
            w.append(audio);
        }
        System.out.printf("LIVE joined: %d bytes, %.2f s%n", w.bytes(), w.seconds());
        assertThat(Mp3.parse(joined.toByteArray()).seconds()).isEqualTo(w.seconds());
    }
}
