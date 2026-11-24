package app.howlers.podcast;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.podcast.PodcastDtos.AudioGeneration;
import app.howlers.podcast.PodcastDtos.AudioJob;
import app.howlers.podcast.PodcastDtos.Capabilities;
import app.howlers.podcast.PodcastDtos.CreateEpisode;
import app.howlers.podcast.PodcastDtos.DraftRequest;
import app.howlers.podcast.PodcastDtos.EpisodeDetail;
import app.howlers.podcast.PodcastDtos.EpisodeSummary;
import app.howlers.podcast.PodcastDtos.Home;
import app.howlers.podcast.PodcastDtos.ProgressInput;
import app.howlers.podcast.PodcastDtos.Segment;
import app.howlers.podcast.PodcastDtos.TranscriptInput;
import app.howlers.podcast.PodcastDtos.UpdateEpisode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Podcasts")
@RestController
@RequestMapping("/api/v1")
public class PodcastController {
    private final PodcastService podcasts;
    private final ScriptDrafter drafter;
    private final AudioGenerator audio;

    public PodcastController(PodcastService podcasts, ScriptDrafter drafter, AudioGenerator audio) {
        this.podcasts = podcasts;
        this.drafter = drafter;
        this.audio = audio;
    }

    private static AuthUser require(AuthUser user) {
        if (user == null) throw ApiException.unauthorized("Authentication required");
        return user;
    }

    @GetMapping("/podcasts/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(drafter.available(), audio.available(), false, podcasts.maxUploadMb(),
                List.of("audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav"), List.of("video/mp4", "video/webm"));
    }

    @GetMapping("/podcasts")
    public CursorPage<EpisodeSummary> list(@RequestParam(required = false) String type, @RequestParam(required = false) String community,
                                           @RequestParam(defaultValue = "recent") String sort, @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit, @AuthenticationPrincipal AuthUser user) {
        return podcasts.list(type, community, sort, cursor, Cursors.clampLimit(limit, 12, 50), user);
    }

    @GetMapping("/podcasts/home")
    public Home home(@AuthenticationPrincipal AuthUser user) {
        return podcasts.home(user);
    }

    @GetMapping("/me/podcasts/history")
    public CursorPage<EpisodeSummary> history(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit) {
        return podcasts.history(user, cursor, Cursors.clampLimit(limit, 20, 50));
    }

    @GetMapping("/podcasts/episodes/{id}")
    public EpisodeDetail detail(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return podcasts.detail(id, user);
    }

    @GetMapping("/podcasts/episodes/{id}/transcript")
    public List<Segment> transcript(@PathVariable long id, @RequestParam(required = false) String q, @AuthenticationPrincipal AuthUser user) {
        return podcasts.transcript(id, user, q);
    }

    @GetMapping("/podcasts/episodes/{id}/transcript.vtt")
    public ResponseEntity<String> vtt(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8")).body(podcasts.transcriptVtt(id, user));
    }

    @PutMapping("/podcasts/episodes/{id}/progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void progress(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody ProgressInput req) {
        podcasts.progress(id, require(user), req.positionSeconds(), req.completed());
    }

    @PutMapping("/podcasts/episodes/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        podcasts.save(id, require(user), true);
    }

    @DeleteMapping("/podcasts/episodes/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        podcasts.save(id, require(user), false);
    }

    // ------------------------------------------------------------------ studio

    @GetMapping("/communities/{slug}/podcasts/studio")
    public List<EpisodeSummary> studio(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return podcasts.studio(slug, require(user));
    }

    @PostMapping("/communities/{slug}/podcasts/episodes")
    @ResponseStatus(HttpStatus.CREATED)
    public EpisodeDetail create(@PathVariable String slug, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateEpisode req) {
        return podcasts.detail(podcasts.create(slug, require(user), req), user);
    }

    @PatchMapping("/podcasts/episodes/{id}")
    public EpisodeDetail update(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateEpisode req) {
        podcasts.update(id, require(user), req);
        return podcasts.detail(id, user);
    }

    @PostMapping(value = "/podcasts/episodes/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EpisodeDetail media(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds) {
        podcasts.uploadMedia(id, require(user), file, durationSeconds);
        return podcasts.detail(id, user);
    }

    @PostMapping(value = "/podcasts/episodes/{id}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EpisodeDetail thumbnail(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        podcasts.uploadThumbnail(id, require(user), file);
        return podcasts.detail(id, user);
    }

    @PutMapping("/podcasts/episodes/{id}/transcript")
    public Map<String, Integer> transcript(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody TranscriptInput req) {
        return Map.of("segments", podcasts.replaceTranscript(id, require(user), req.format(), req.content()));
    }

    @PostMapping("/podcasts/episodes/{id}/{action:submit|publish|unpublish}")
    public EpisodeDetail transition(@PathVariable long id, @PathVariable String action, @AuthenticationPrincipal AuthUser user) {
        podcasts.transition(id, require(user), action);
        return podcasts.detail(id, user);
    }

    @DeleteMapping("/podcasts/episodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        podcasts.delete(id, require(user));
    }

    @PostMapping("/podcasts/episodes/{id}/draft-script")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EpisodeDetail draft(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody DraftRequest req) {
        drafter.start(id, require(user), req.topic(), req.targetMinutes());
        return podcasts.detail(id, user);
    }

    // ------------------------------------------------------------------ audio generation (D-036)

    @GetMapping("/podcasts/episodes/{id}/audio-generation")
    public AudioGeneration audioStatus(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return audio.status(id, require(user));
    }

    @PostMapping("/podcasts/episodes/{id}/audio-generation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AudioJob generateAudio(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return audio.start(id, require(user));
    }

    @PostMapping("/podcasts/episodes/{id}/audio-generation/cancel")
    public AudioJob cancelAudio(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return audio.cancel(id, require(user));
    }
}
