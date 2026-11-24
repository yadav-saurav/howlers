package app.howlers.podcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.podcast.PodcastDtos.AudioGeneration;
import app.howlers.podcast.PodcastDtos.AudioJob;
import app.howlers.podcast.PodcastDtos.CreateEpisode;
import app.howlers.podcast.PodcastDtos.EpisodeSummary;
import app.howlers.podcast.PodcastDtos.Segment;
import app.howlers.podcast.PodcastDtos.UpdateEpisode;
import app.howlers.podcast.tts.Mp3;
import app.howlers.podcast.tts.TtsProvider;
import app.howlers.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** Audio generation end to end with a fake TTS provider (no network): job states, budget, cancel, transcript, labels. */
@AutoConfigureMockMvc
class PodcastAudioIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PodcastService podcasts;
    @Autowired AudioGenerator audio;
    @Autowired MockMvc mvc;

    /** Returns tagged MP3 parts (ID3v2 + Info frame + silence + ID3v1) one frame (26 ms) per character, like a real API would. */
    static class FakeTts implements TtsProvider {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Function<Integer, byte[]> behaviour;

        FakeTts(Function<Integer, byte[]> behaviour) {
            this.behaviour = behaviour;
        }

        @Override public String id() { return "fake"; }
        @Override public String label() { return "Fake TTS"; }
        @Override public String model() { return "fake-model-1"; }
        @Override public boolean available() { return true; }
        @Override public Voice voice(Speaker s) { return new Voice(s, s == Speaker.A ? "voice-a" : "voice-b", s == Speaker.A ? "Ada" : "Bo"); }

        @Override
        public byte[] synthesize(String text, Speaker speaker) throws TtsException, InterruptedException {
            calls.add(speaker + ":" + text);
            byte[] special = behaviour == null ? null : behaviour.apply(calls.size());
            if (special != null) return special;
            return part(Math.max(1, text.length()));
        }

        static byte[] part(int frames) {
            byte[] id3 = new byte[30];
            id3[0] = 'I'; id3[1] = 'D'; id3[2] = '3'; id3[3] = 4; id3[9] = 20;
            byte[] info = Mp3.silence44k(1);
            System.arraycopy("Info".getBytes(StandardCharsets.ISO_8859_1), 0, info, 21, 4);
            byte[] tag = new byte[128];
            tag[0] = 'T'; tag[1] = 'A'; tag[2] = 'G';
            byte[] body = Mp3.silence44k(frames);
            byte[] out = new byte[id3.length + info.length + body.length + tag.length];
            System.arraycopy(id3, 0, out, 0, id3.length);
            System.arraycopy(info, 0, out, id3.length, info.length);
            System.arraycopy(body, 0, out, id3.length + info.length, body.length);
            System.arraycopy(tag, 0, out, out.length - tag.length, tag.length);
            return out;
        }
    }

    @AfterEach
    void restore() {
        audio.useProvider(TtsProvider.NONE);
        audio.overrideDailyBudget(null);
    }

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@a.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    record Setup(AuthUser owner, AuthUser member, String slug, long id) {}

    private Setup setup(String prefix, String script) {
        String s = prefix + System.nanoTime() % 1_000_000;
        AuthUser owner = user(s + "o"), member = user(s + "m");
        communities.create(owner, new CreateCommunityRequest(s, "Audio", "", null, "PUBLIC", List.of(), List.of()));
        communities.join(s, member);
        long id = podcasts.create(s, owner, new CreateEpisode("Generated episode", null, "DEEP_DIVE"));
        if (script != null) podcasts.update(id, owner, new UpdateEpisode(null, null, null, null, script, null, null));
        return new Setup(owner, member, s, id);
    }

    private static final String LONG_TURN = "This sentence keeps the monologue going for a while. ".repeat(56).strip(); // ~2.9k chars

    static final String SCRIPT = """
            # Generated episode
            A summary that is not read aloud [1].

            ## Outline
            1. Intro
            2. Deep dive

            ## Intro (≈00:00)
            **HOST A:** Welcome to the show [1].
            **HOST B:** Thanks. Let's get into it.

            ## Deep dive (≈01:00)
            **HOST A:** %s
            **HOST B:** That was long.

            ---

            ## Sources
            [1] A thread (c/x) /c/x/p/1
            """.formatted(LONG_TURN);

    private AudioJob await(long id, AuthUser owner, Set<String> until) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        AudioJob j = null;
        while (System.currentTimeMillis() < deadline) {
            j = audio.status(id, owner).job();
            if (j != null && until.contains(j.status())) return j;
            Thread.sleep(25);
        }
        throw new AssertionError("Job didn't reach " + until + ": " + j);
    }

    @Test
    void unavailableWithoutAProvider() throws Exception {
        Setup t = setup("au", SCRIPT);
        AudioGeneration g = audio.status(t.id(), t.owner());
        assertThat(g.available()).isFalse();
        assertThat(g.voices()).isEmpty();
        assertThat(g.blockers()).contains("Audio generation isn't configured on this server");
        assertThatThrownBy(() -> audio.start(t.id(), t.owner())).isInstanceOf(ApiException.class).hasMessageContaining("isn't configured");
        mvc.perform(get("/api/v1/podcasts/capabilities")).andExpect(jsonPath("$.audioGeneration").value(false));
        mvc.perform(get("/api/v1/podcasts/episodes/" + t.id() + "/audio-generation")).andExpect(status().isUnauthorized());
    }

    @Test
    void generatesLabelledAudioWithTimedTranscriptAndChapters() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeTts fake = new FakeTts(n -> {
            if (n == 1) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });
        audio.useProvider(fake);
        Setup t = setup("ag", SCRIPT);
        mvc.perform(get("/api/v1/podcasts/capabilities")).andExpect(jsonPath("$.audioGeneration").value(true));

        // The estimate is shown before starting: spoken characters only, the long turn split under the request limit.
        AudioGeneration g = audio.status(t.id(), t.owner());
        assertThat(g.available()).isTrue();
        assertThat(g.provider()).isEqualTo("fake");
        assertThat(g.voices()).extracting(PodcastDtos.AudioVoice::name).containsExactly("Ada", "Bo");
        assertThat(g.estimate().turns()).isEqualTo(4);
        assertThat(g.estimate().chunks()).isEqualTo(5);
        assertThat(g.estimate().sections()).isEqualTo(2);
        // The space where the long turn is cut isn't sent, so it isn't counted.
        int expected = "Welcome to the show.".length() + "Thanks. Let's get into it.".length() + LONG_TURN.length() - 1 + "That was long.".length();
        assertThat(g.estimate().characters()).isEqualTo(expected);
        assertThat(g.blockers()).isEmpty();
        assertThat(g.job()).isNull();
        assertThat(g.budget().usedToday()).isZero();
        assertThatThrownBy(() -> audio.status(t.id(), t.member())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> audio.start(t.id(), t.member())).isInstanceOf(ApiException.class);

        AudioJob started = audio.start(t.id(), t.owner());
        assertThat(started.status()).isIn("QUEUED", "GENERATING");
        assertThat(started.characters()).isEqualTo(expected);
        assertThat(started.chunksTotal()).isEqualTo(5);
        // While it runs: no second job, no manual upload over it, and the budget already counts it.
        assertThatThrownBy(() -> audio.start(t.id(), t.owner())).isInstanceOf(ApiException.class).hasMessageContaining("already");
        assertThatThrownBy(() -> podcasts.uploadMedia(t.id(), t.owner(), new MockMultipartFile("file", "x.mp3", "audio/mpeg", Mp3.silence44k(4)), 1))
                .isInstanceOf(ApiException.class).hasMessageContaining("being generated");
        assertThat(audio.status(t.id(), t.owner()).budget().usedToday()).isEqualTo(expected);
        gate.countDown();

        AudioJob done = await(t.id(), t.owner(), Set.of("READY", "FAILED", "CANCELLED"));
        assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo("READY");
        assertThat(done.chunksDone()).isEqualTo(5);
        assertThat(done.charactersSent()).isEqualTo(expected);
        assertThat(fake.calls).hasSize(5);
        assertThat(fake.calls.getFirst()).isEqualTo("A:Welcome to the show.");
        assertThat(fake.calls).allMatch(c -> c.length() <= 2 + 2_500);

        // The episode is labelled as AI voice, everywhere it's read.
        var d = podcasts.detail(t.id(), t.owner());
        assertThat(d.mediaKind()).isEqualTo("AUDIO");
        assertThat(d.mediaContentType()).isEqualTo("audio/mpeg");
        assertThat(d.audioOrigin()).isEqualTo("AI_TTS");
        assertThat(d.audioProvider()).isEqualTo("fake");
        assertThat(d.audioModel()).isEqualTo("fake-model-1");
        assertThat(d.durationSeconds()).isEqualTo((int) Math.round(done.durationSeconds()));
        assertThat(podcasts.studio(t.slug(), t.owner())).extracting(EpisodeSummary::audioOrigin).containsExactly("AI_TTS");

        // Transcript: one segment per chunk, timed from the audio, with the pause between turns.
        List<Segment> segs = podcasts.transcript(t.id(), t.owner(), null);
        assertThat(segs).hasSize(5);
        assertThat(segs).extracting(Segment::speaker).containsExactly("Host A", "Host B", "Host A", "Host A", "Host B");
        assertThat(segs.getFirst().startSeconds()).isZero();
        double frame = 1152.0 / 44100;
        assertThat(segs.getFirst().endSeconds()).isCloseTo("Welcome to the show.".length() * frame, within(0.002));
        for (int i = 1; i < segs.size(); i++) {
            assertThat(segs.get(i).startSeconds()).isGreaterThanOrEqualTo(segs.get(i - 1).endSeconds());
        }
        assertThat(segs.get(1).startSeconds() - segs.get(0).endSeconds()).as("pause between turns").isGreaterThan(0.3);
        assertThat(segs.get(3).startSeconds()).as("no pause inside a split turn").isCloseTo(segs.get(2).endSeconds(), within(0.002));
        assertThat(segs.getLast().endSeconds()).isCloseTo(done.durationSeconds(), within(0.002));
        assertThat(d.chapters()).extracting(PodcastDtos.Chapter::title).containsExactly("Intro", "Deep dive");
        assertThat(d.chapters().getFirst().startSeconds()).isZero();
        assertThat(d.chapters().get(1).startSeconds()).isEqualTo((int) Math.floor(segs.get(2).startSeconds()));

        // Stored like an upload: range requests work, and the file is one clean MP3 whose length matches the transcript.
        mvc.perform(get(d.mediaUrl()).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Type", "audio/mpeg"));
        byte[] file = mvc.perform(get(d.mediaUrl())).andReturn().getResponse().getContentAsByteArray();
        assertThat(Mp3.parse(file).seconds()).isCloseTo(done.durationSeconds(), within(0.002));
        assertThat(new String(file, StandardCharsets.ISO_8859_1)).doesNotContain("ID3").doesNotContain("TAG").doesNotContain("Info");
        mvc.perform(get("/api/v1/podcasts/episodes/" + t.id())).andExpect(status().isNotFound()); // still a draft

        // It can go through review and publish; published episodes can't be regenerated.
        podcasts.transition(t.id(), t.owner(), "submit");
        podcasts.transition(t.id(), t.owner(), "publish");
        mvc.perform(get("/api/v1/podcasts/episodes/" + t.id()))
                .andExpect(jsonPath("$.audioOrigin").value("AI_TTS"))
                .andExpect(jsonPath("$.audioProvider").value("fake"))
                .andExpect(jsonPath("$.audioModel").value("fake-model-1"));
        assertThat(audio.status(t.id(), t.owner()).blockers()).anyMatch(b -> b.contains("Unpublish"));
        assertThatThrownBy(() -> audio.start(t.id(), t.owner())).hasMessageContaining("Unpublish");

        // Uploading real media replaces the label.
        podcasts.transition(t.id(), t.owner(), "unpublish");
        String oldUrl = d.mediaUrl();
        podcasts.uploadMedia(t.id(), t.owner(), new MockMultipartFile("file", "x.mp3", "audio/mpeg", Mp3.silence44k(40)), 1);
        var after = podcasts.detail(t.id(), t.owner());
        assertThat(after.audioOrigin()).isEqualTo("UPLOADED");
        assertThat(after.audioProvider()).isNull();
        mvc.perform(get(oldUrl)).andExpect(status().isNotFound()); // the generated file was replaced
    }

    @Test
    void cancelStopsTheJobAndOnlyChargesWhatWasSent() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        FakeTts fake = new FakeTts(n -> {
            if (n >= 2) {
                secondStarted.countDown();
                try {
                    Thread.sleep(30_000); // a slow request; cancel interrupts it
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return null;
        });
        audio.useProvider(fake);
        Setup t = setup("ac", SCRIPT);
        assertThatThrownBy(() -> audio.cancel(t.id(), t.owner())).hasMessageContaining("No audio generation");
        audio.start(t.id(), t.owner());
        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> audio.cancel(t.id(), t.member())).isInstanceOf(ApiException.class);
        AudioJob c = audio.cancel(t.id(), t.owner());
        assertThat(c.cancelRequested()).isTrue();

        AudioJob done = await(t.id(), t.owner(), Set.of("CANCELLED", "FAILED", "READY"));
        assertThat(done.status()).isEqualTo("CANCELLED");
        assertThat(done.chunksDone()).isEqualTo(1);
        assertThat(done.charactersSent()).isEqualTo("Welcome to the show.".length());
        assertThat(audio.status(t.id(), t.owner()).budget().usedToday()).isEqualTo(done.charactersSent());
        var d = podcasts.detail(t.id(), t.owner());
        assertThat(d.mediaUrl()).isNull();
        assertThat(d.audioOrigin()).isNull();
        assertThat(d.hasTranscript()).isFalse();
    }

    @Test
    void failuresAreReportedAndTheDailyBudgetIsEnforced() throws Exception {
        FakeTts failing = new FakeTts(n -> {
            throw new IllegalArgumentException("unused");
        }) {
            @Override
            public byte[] synthesize(String text, Speaker speaker) throws TtsException {
                throw new TtsException("ElevenLabs rejected the request (voice_not_found)", false);
            }
        };
        audio.useProvider(failing);
        Setup t = setup("af", SCRIPT);
        audio.start(t.id(), t.owner());
        AudioJob failed = await(t.id(), t.owner(), Set.of("FAILED", "READY", "CANCELLED"));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("ElevenLabs rejected the request (voice_not_found)");
        assertThat(failed.charactersSent()).isZero();
        assertThat(podcasts.detail(t.id(), t.owner()).mediaUrl()).isNull();

        // A new budget that the script doesn't fit in blocks the start, with the numbers shown.
        audio.useProvider(new FakeTts(null));
        audio.overrideDailyBudget(1_000);
        AudioGeneration g = audio.status(t.id(), t.owner());
        assertThat(g.budget().dailyLimit()).isEqualTo(1_000);
        assertThat(g.budget().remaining()).isEqualTo(1_000); // the failed job sent nothing, so it costs nothing
        assertThat(g.blockers()).anyMatch(b -> b.contains("left today"));
        assertThatThrownBy(() -> audio.start(t.id(), t.owner())).hasMessageContaining("left today");

        // Scripts without host lines can't be read.
        podcasts.update(t.id(), t.owner(), new UpdateEpisode(null, null, null, null, "# Notes\nJust notes, no dialogue.", null, null));
        audio.overrideDailyBudget(null);
        assertThat(audio.status(t.id(), t.owner()).blockers()).contains("The script has no HOST A / HOST B lines to read");
        Setup empty = setup("ae", null);
        assertThat(audio.status(empty.id(), empty.owner()).blockers()).contains("Write or draft a script first");
        assertThat(audio.status(empty.id(), empty.owner()).estimate().characters()).isZero();
    }
}
