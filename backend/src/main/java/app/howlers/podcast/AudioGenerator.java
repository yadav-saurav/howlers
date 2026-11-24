package app.howlers.podcast;

import app.howlers.common.error.ApiException;
import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.text.Tokens;
import app.howlers.community.Community;
import app.howlers.podcast.PodcastDtos.AudioBudget;
import app.howlers.podcast.PodcastDtos.AudioEstimate;
import app.howlers.podcast.PodcastDtos.AudioGeneration;
import app.howlers.podcast.PodcastDtos.AudioJob;
import app.howlers.podcast.PodcastDtos.AudioVoice;
import app.howlers.podcast.PodcastDtos.ChapterInput;
import app.howlers.podcast.tts.Mp3;
import app.howlers.podcast.tts.SpeechScript;
import app.howlers.podcast.tts.TtsProperties;
import app.howlers.podcast.tts.TtsProvider;
import app.howlers.podcast.tts.TtsProvider.Speaker;
import app.howlers.storage.StorageService;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates an episode's audio from its two-host script with the configured {@link TtsProvider} (D-036).
 *
 * <p>Pipeline, on the shared podcast executor: split the saved script into HOST A / HOST B turns → cut long turns into
 * chunks under the provider's per-request limit at sentence boundaries → synthesise each chunk → join the MP3 frames
 * (tags and Xing headers stripped, short silences between turns) into a temp file → store it through
 * {@link StorageService} exactly like an upload → write a transcript timed from each chunk's measured MP3 duration and
 * chapters from the script's sections. The episode is marked {@code audio_origin = AI_TTS} with provider and model, which
 * the API and UI surface as an "AI-generated voice" label.
 *
 * <p>Jobs move QUEUED → GENERATING → READY | FAILED | CANCELLED. A per-community daily character budget caps cost:
 * active and finished jobs count their full size, failed and cancelled ones what was actually sent.
 */
@Component
public class AudioGenerator {
    private static final Logger log = LoggerFactory.getLogger(AudioGenerator.class);
    private static final Set<String> ACTIVE = Set.of("QUEUED", "GENERATING");

    private final JdbcClient jdbc;
    private final PodcastService podcasts;
    private final StorageService storage;
    private final RateLimiter limiter;
    private final TransactionTemplate tx;
    private final PodcastJobs executor;
    private final TtsProperties props;
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();
    private volatile TtsProvider provider;
    private volatile Integer budgetOverride;

    public AudioGenerator(JdbcClient jdbc, PodcastService podcasts, StorageService storage, RateLimiter limiter, TransactionTemplate tx,
                          PodcastJobs executor, TtsProperties props, TtsProvider provider) {
        this.jdbc = jdbc;
        this.podcasts = podcasts;
        this.storage = storage;
        this.limiter = limiter;
        this.tx = tx;
        this.executor = executor;
        this.props = props;
        this.provider = provider;
    }

    public boolean available() {
        return provider.available();
    }

    /** Test seam: swaps the provider (integration tests use a fake that never touches the network). */
    void useProvider(TtsProvider p) {
        this.provider = p;
    }

    /** Test seam: overrides the daily budget; null restores the configured value. */
    void overrideDailyBudget(Integer chars) {
        this.budgetOverride = chars;
    }

    int dailyBudget() {
        Integer o = budgetOverride;
        return o != null ? o : props.dailyBudget();
    }

    /** A restart interrupts in-flight generations; mark them failed so the studio doesn't wait forever. */
    @EventListener(ApplicationReadyEvent.class)
    void recoverInterrupted() {
        jdbc.sql("""
                UPDATE podcast_tts_jobs SET status = 'FAILED', error = 'Interrupted by a server restart. Try again.', finished_at = now()
                WHERE status IN ('QUEUED', 'GENERATING')""").update();
    }

    // ------------------------------------------------------------------ read

    record JobRow(long id, long episodeId, long communityId, String status, String provider, String model, String voiceA, String voiceB,
                  int characters, int charactersSent, int chunksTotal, int chunksDone, boolean cancelRequested, Double duration,
                  String error, Instant createdAt, Instant startedAt, Instant finishedAt) {
        AudioJob dto() {
            return new AudioJob(id, status, provider, model, characters, charactersSent, chunksTotal, chunksDone, duration, error,
                    cancelRequested, createdAt, startedAt, finishedAt);
        }
    }

    private static JobRow job(ResultSet rs, int i) throws SQLException {
        java.math.BigDecimal d = rs.getBigDecimal("duration_seconds");
        return new JobRow(rs.getLong("id"), rs.getLong("episode_id"), rs.getLong("community_id"), rs.getString("status"),
                rs.getString("provider"), rs.getString("model"), rs.getString("voice_a"), rs.getString("voice_b"), rs.getInt("characters"),
                rs.getInt("characters_sent"), rs.getInt("chunks_total"), rs.getInt("chunks_done"), rs.getBoolean("cancel_requested"),
                d == null ? null : d.doubleValue(), rs.getString("error"), ts(rs, "created_at"), ts(rs, "started_at"), ts(rs, "finished_at"));
    }

    Optional<JobRow> loadJob(long jobId) {
        return jdbc.sql("SELECT * FROM podcast_tts_jobs WHERE id = ?").param(jobId).query(AudioGenerator::job).optional();
    }

    private Optional<JobRow> latest(long episodeId) {
        return jdbc.sql("SELECT * FROM podcast_tts_jobs WHERE episode_id = ? ORDER BY id DESC LIMIT 1").param(episodeId)
                .query(AudioGenerator::job).optional();
    }

    int usedToday(long communityId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN status IN ('QUEUED', 'GENERATING', 'READY') THEN characters ELSE characters_sent END), 0)
                        FROM podcast_tts_jobs
                        WHERE community_id = ? AND created_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')""")
                .param(communityId).query(Integer.class).single();
    }

    private AudioBudget budget(long communityId) {
        int limit = dailyBudget();
        int used = usedToday(communityId);
        Instant reset = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new AudioBudget(limit, used, Math.max(0, limit - used), reset);
    }

    private List<AudioVoice> voices() {
        TtsProvider p = provider;
        if (!p.available()) return List.of();
        return List.of(voice(p, Speaker.A), voice(p, Speaker.B));
    }

    private static AudioVoice voice(TtsProvider p, Speaker s) {
        TtsProvider.Voice v = p.voice(s);
        return new AudioVoice(s == Speaker.A ? "HOST A" : "HOST B", v.voiceId(), v.name());
    }

    record Plan(SpeechScript.Parsed parsed, List<SpeechScript.Chunk> chunks, int characters) {}

    private Plan plan(String script) {
        SpeechScript.Parsed parsed = SpeechScript.parse(script);
        List<SpeechScript.Chunk> chunks = SpeechScript.chunk(parsed.turns(), props.perRequest());
        return new Plan(parsed, chunks, chunks.stream().mapToInt(c -> c.text().length()).sum());
    }

    /** Why a generation can't start right now (empty when it can). */
    private List<String> blockers(PodcastService.Row r, Plan plan, AudioBudget budget, Optional<JobRow> last) {
        List<String> out = new ArrayList<>();
        if (!provider.available()) out.add("Audio generation isn't configured on this server");
        if (last.isPresent() && ACTIVE.contains(last.get().status())) out.add("Audio is already being generated");
        if ("PUBLISHED".equals(r.status())) out.add("Unpublish the episode before generating new audio");
        if ("GENERATING".equals(r.scriptStatus())) out.add("Wait for the script draft to finish");
        if (r.script() == null || r.script().isBlank()) out.add("Write or draft a script first");
        else if (plan.chunks().isEmpty()) out.add("The script has no HOST A / HOST B lines to read");
        if (plan.characters() > props.perEpisode()) {
            out.add("The script is " + plan.characters() + " characters; the limit per generation is " + props.perEpisode());
        }
        if (plan.characters() > 0 && plan.characters() > budget.remaining()) {
            out.add("This needs " + plan.characters() + " characters but the community has " + budget.remaining()
                    + " left today (daily budget " + budget.dailyLimit() + ")");
        }
        return out;
    }

    public AudioGeneration status(long episodeId, AuthUser user) {
        PodcastService.Row r = podcasts.load(episodeId);
        Community c = podcasts.requireManager(r, user);
        Plan plan = plan(r.script());
        AudioBudget budget = budget(c.getId());
        Optional<JobRow> last = latest(episodeId);
        TtsProvider p = provider;
        return new AudioGeneration(p.available(), p.available() ? p.id() : null, p.available() ? p.label() : null,
                p.available() ? p.model() : null, voices(),
                new AudioEstimate(plan.characters(), plan.chunks().size(), plan.parsed().turns().size(), plan.parsed().sections().size(),
                        props.perRequest(), props.perEpisode()),
                budget, last.map(JobRow::dto).orElse(null), blockers(r, plan, budget, last));
    }

    // ------------------------------------------------------------------ start / cancel

    public AudioJob start(long episodeId, AuthUser user) {
        TtsProvider p = provider;
        if (!p.available()) throw ApiException.badRequest("Audio generation isn't configured on this server");
        PodcastService.Row r0 = podcasts.load(episodeId);
        podcasts.requireManager(r0, user);
        limiter.check("podcast:tts:" + user.id(), 10, Duration.ofDays(1));
        long jobId;
        try {
            jobId = tx.execute(s -> {
                PodcastService.Row r = podcasts.load(episodeId);
                Community c = podcasts.requireManager(r, user);
                // Serialise starts per community so two moderators can't both spend the last of the budget.
                jdbc.sql("SELECT pg_advisory_xact_lock(29811, ?)").param((int) c.getId().longValue()).query((rs, i) -> 1).list();
                Plan plan = plan(r.script());
                Optional<JobRow> last = latest(episodeId);
                List<String> blocked = blockers(r, plan, budget(c.getId()), last);
                if (!blocked.isEmpty()) {
                    if (last.isPresent() && ACTIVE.contains(last.get().status())) throw ApiException.conflict(null, blocked.getFirst());
                    throw ApiException.badRequest(blocked.getFirst());
                }
                return jdbc.sql("""
                                INSERT INTO podcast_tts_jobs (episode_id, community_id, requested_by, provider, model, voice_a, voice_b,
                                                              characters, chunks_total)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                        .params(episodeId, c.getId(), user.id(), p.id(), p.model(), p.voice(Speaker.A).voiceId(), p.voice(Speaker.B).voiceId(),
                                plan.characters(), plan.chunks().size())
                        .query(Long.class).single();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(null, "Audio is already being generated");
        }
        Future<?> f = executor.submit(() -> run(jobId, p));
        running.put(jobId, f);
        if (f.isDone()) running.remove(jobId);
        log.info("Queued audio generation job {} for episode {}", jobId, episodeId);
        return loadJob(jobId).map(JobRow::dto).orElseThrow();
    }

    public AudioJob cancel(long episodeId, AuthUser user) {
        PodcastService.Row r = podcasts.load(episodeId);
        podcasts.requireManager(r, user);
        JobRow j = latest(episodeId).filter(x -> ACTIVE.contains(x.status()))
                .orElseThrow(() -> ApiException.badRequest("No audio generation is running for this episode"));
        jdbc.sql("UPDATE podcast_tts_jobs SET cancel_requested = TRUE WHERE id = ?").param(j.id()).update();
        // A queued job hasn't started: cancel it now. A running one stops at the next chunk (or sooner, by interrupt).
        jdbc.sql("UPDATE podcast_tts_jobs SET status = 'CANCELLED', finished_at = now() WHERE id = ? AND status = 'QUEUED'").param(j.id()).update();
        Future<?> f = running.get(j.id());
        if (f != null) f.cancel(true);
        return loadJob(j.id()).map(JobRow::dto).orElseThrow();
    }

    // ------------------------------------------------------------------ the job

    static final class Cancelled extends Exception {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    private boolean cancelRequested(long jobId) {
        return jdbc.sql("SELECT cancel_requested FROM podcast_tts_jobs WHERE id = ?").param(jobId).query(Boolean.class).optional().orElse(true);
    }

    void run(long jobId, TtsProvider p) {
        Path tmp = null;
        String key = null;
        try {
            int claimed = jdbc.sql("""
                            UPDATE podcast_tts_jobs SET status = 'GENERATING', started_at = now()
                            WHERE id = ? AND status = 'QUEUED' AND cancel_requested = FALSE""").param(jobId).update();
            if (claimed == 0) {
                finishCancelled(jobId);
                return;
            }
            JobRow j = loadJob(jobId).orElseThrow(Cancelled::new);
            String script = jdbc.sql("SELECT script FROM podcast_episodes WHERE id = ?").param(j.episodeId()).query(String.class).optional()
                    .orElseThrow(Cancelled::new);
            Plan plan = plan(script);
            if (plan.characters() != j.characters() || plan.chunks().size() != j.chunksTotal()) {
                fail(jobId, "The script changed after generation was queued. Start again.");
                return;
            }
            tmp = Files.createTempFile("howlers-tts-", ".mp3");
            List<TranscriptParser.Segment> segments = new ArrayList<>();
            Map<Integer, Double> sectionStarts = new LinkedHashMap<>();
            double seconds;
            long bytes;
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                Mp3.Writer w = new Mp3.Writer(os);
                int done = 0;
                for (SpeechScript.Chunk ch : plan.chunks()) {
                    if (Thread.currentThread().isInterrupted() || cancelRequested(jobId)) throw new Cancelled();
                    if (ch.firstOfTurn() && done > 0) w.silence(props.pauseMs());
                    double start = w.seconds();
                    byte[] audio = p.synthesize(ch.text(), ch.speaker());
                    w.append(audio);
                    segments.add(new TranscriptParser.Segment(round3(start), round3(w.seconds()), SpeechScript.label(ch.speaker()), ch.text()));
                    if (ch.section() >= 0) sectionStarts.putIfAbsent(ch.section(), start);
                    done++;
                    int updated = jdbc.sql("UPDATE podcast_tts_jobs SET chunks_done = ?, characters_sent = characters_sent + ? WHERE id = ?")
                            .params(done, ch.text().length(), jobId).update();
                    if (updated == 0) throw new Cancelled(); // the episode (and its job) was deleted
                }
                seconds = w.seconds();
                bytes = w.bytes();
            }
            if (Thread.currentThread().isInterrupted() || cancelRequested(jobId)) throw new Cancelled();
            key = "podcasts/" + Tokens.random(18) + ".mp3";
            try (InputStream in = Files.newInputStream(tmp)) {
                storage.put(key, in, "audio/mpeg");
            }
            List<ChapterInput> chapters = new ArrayList<>();
            Set<Integer> starts = new HashSet<>();
            for (SpeechScript.Section s : plan.parsed().sections()) {
                Double at = sectionStarts.get(s.index());
                if (at == null) continue;
                int sec = (int) Math.floor(at);
                if (starts.add(sec)) chapters.add(new ChapterInput(sec, s.title()));
            }
            String stored = key;
            String oldKey = tx.execute(s -> commit(j, p, stored, bytes, seconds, segments, chapters));
            key = null; // owned by the episode now
            if (oldKey != null) storage.delete(oldKey);
            log.info("Generated {} s of audio for episode {} (job {}, {} chunks, {} characters)",
                    Math.round(seconds), j.episodeId(), jobId, plan.chunks().size(), plan.characters());
        } catch (Cancelled | InterruptedException e) {
            Thread.interrupted();
            finishCancelled(jobId);
        } catch (TtsProvider.TtsException e) {
            Thread.interrupted();
            if (cancelRequested(jobId)) finishCancelled(jobId);
            else fail(jobId, e.getMessage());
        } catch (ApiException e) {
            Thread.interrupted();
            fail(jobId, e.getMessage());
        } catch (Exception e) {
            boolean interrupted = Thread.interrupted();
            if (interrupted || cancelRequested(jobId)) {
                finishCancelled(jobId);
            } else {
                log.warn("Audio generation job {} failed: {}", jobId, e.getClass().getSimpleName());
                fail(jobId, "Generation failed (" + e.getClass().getSimpleName() + "). Try again in a moment.");
            }
        } finally {
            running.remove(jobId);
            if (key != null) {
                try {
                    storage.delete(key);
                } catch (RuntimeException ignored) {
                    // best effort: an orphaned object is harmless
                }
            }
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp dir cleanup will catch it
                }
            }
        }
    }

    /** Attaches the audio, transcript and chapters to the episode. Returns the replaced media key to delete. */
    private String commit(JobRow j, TtsProvider p, String key, long bytes, double seconds, List<TranscriptParser.Segment> segments,
                          List<ChapterInput> chapters) {
        String status = jdbc.sql("SELECT status FROM podcast_episodes WHERE id = ? FOR UPDATE").param(j.episodeId()).query(String.class)
                .optional().orElse(null);
        Boolean cancel = jdbc.sql("SELECT cancel_requested FROM podcast_tts_jobs WHERE id = ? FOR UPDATE").param(j.id()).query(Boolean.class)
                .optional().orElse(null);
        if (status == null || cancel == null || cancel) throw new CancelledInTx();
        if ("PUBLISHED".equals(status)) {
            throw ApiException.badRequest("The episode was published while audio was generating. Unpublish it and generate again.");
        }
        String oldKey = jdbc.sql("SELECT media_key FROM podcast_episodes WHERE id = ?").param(j.episodeId()).query(String.class).optional()
                .orElse(null);
        int duration = Math.max(1, (int) Math.round(seconds));
        jdbc.sql("""
                        UPDATE podcast_episodes SET media_kind = 'AUDIO', media_key = ?, media_content_type = 'audio/mpeg', media_bytes = ?,
                               duration_seconds = ?, audio_origin = 'AI_TTS', audio_provider = ?, audio_model = ?, updated_at = now()
                        WHERE id = ?""")
                .params(key, bytes, duration, p.id(), p.model(), j.episodeId()).update();
        podcasts.storeTranscript(j.episodeId(), segments);
        if (!chapters.isEmpty()) podcasts.replaceChapters(j.episodeId(), chapters, duration);
        jdbc.sql("UPDATE podcast_tts_jobs SET status = 'READY', duration_seconds = ?, error = NULL, finished_at = now() WHERE id = ?")
                .params(round3(seconds), j.id()).update();
        return oldKey;
    }

    /** Unchecked so it can leave the transaction callback (rolling it back). */
    static final class CancelledInTx extends RuntimeException {
        CancelledInTx() {
            super("cancelled", null, false, false);
        }
    }

    private void finishCancelled(long jobId) {
        jdbc.sql("UPDATE podcast_tts_jobs SET status = 'CANCELLED', finished_at = now() WHERE id = ? AND status IN ('QUEUED', 'GENERATING')")
                .param(jobId).update();
    }

    private void fail(long jobId, String message) {
        String m = message == null ? "Generation failed" : message.length() > 500 ? message.substring(0, 500) : message;
        jdbc.sql("UPDATE podcast_tts_jobs SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status IN ('QUEUED', 'GENERATING')")
                .params(m, jobId).update();
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
