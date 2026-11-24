package app.howlers.podcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityRole;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.podcast.PodcastDtos.ChapterInput;
import app.howlers.podcast.PodcastDtos.CreateEpisode;
import app.howlers.podcast.PodcastDtos.EpisodeSummary;
import app.howlers.podcast.PodcastDtos.SourceInput;
import app.howlers.podcast.PodcastDtos.UpdateEpisode;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PodcastIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired PodcastService podcasts;
    @Autowired MockMvc mvc;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@p.local", "password1", null), "t", "127.0.0.1").response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    /** A fake-but-well-formed MP3 header followed by filler: enough for magic-byte sniffing and range serving. */
    private static MockMultipartFile mp3(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 7);
        b[0] = 'I'; b[1] = 'D'; b[2] = '3';
        return new MockMultipartFile("file", "episode.mp3", "audio/mpeg", b);
    }

    @Test
    void studioWorkflowVisibilityAndListening() throws Exception {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        AuthUser owner = user("po" + s), listener = user("pl" + s), member = user("pm" + s);
        String slug = "pod" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Pods", "", null, "PUBLIC", List.of(), List.of()));
        communities.join(slug, member);
        long post = posts.create(owner, new CreatePostRequest(slug, "TEXT", "Source discussion", "<p>Content</p>", null, null, null, null, null));

        // Only moderators run the studio.
        assertThatThrownBy(() -> podcasts.create(slug, member, new CreateEpisode("Nope", null, "DEEP_DIVE"))).isInstanceOf(ApiException.class);
        long id = podcasts.create(slug, owner, new CreateEpisode("Rust vs Go, a community debate", "Members weigh in.", "DEBATE"));

        // Drafts are invisible to everyone but managers.
        assertThatThrownBy(() -> podcasts.detail(id, listener)).isInstanceOf(ApiException.class);
        assertThat(podcasts.list(null, slug, "recent", null, 10, listener).items()).isEmpty();

        // Publishing requires review, media and a transcript.
        assertThatThrownBy(() -> podcasts.transition(id, owner, "publish")).isInstanceOf(ApiException.class);
        podcasts.transition(id, owner, "submit");
        assertThatThrownBy(() -> podcasts.transition(id, owner, "publish")).hasMessageContaining("Upload");
        assertThatThrownBy(() -> podcasts.uploadMedia(id, owner, new MockMultipartFile("file", "x.mp3", "audio/mpeg", "%PDF-nope".getBytes()), 60))
                .isInstanceOf(ApiException.class);
        podcasts.uploadMedia(id, owner, mp3(4096), 3000);
        assertThatThrownBy(() -> podcasts.transition(id, owner, "publish")).hasMessageContaining("transcript");
        assertThat(podcasts.replaceTranscript(id, owner, "timestamped", "[00:00] Host A: Welcome\n[10:00] Host B: Rust first")).isEqualTo(2);
        podcasts.update(id, owner, new UpdateEpisode(null, null, null, true, null,
                List.of(new ChapterInput(0, "Intro"), new ChapterInput(600, "Rust")), List.of(new SourceInput(post, null))));
        assertThatThrownBy(() -> podcasts.update(id, owner, new UpdateEpisode(null, null, null, null, null,
                List.of(new ChapterInput(9_999, "Too late")), null))).isInstanceOf(ApiException.class);
        podcasts.transition(id, owner, "publish");

        var detail = podcasts.detail(id, listener);
        assertThat(detail.status()).isEqualTo("PUBLISHED");
        assertThat(detail.script()).isNull(); // scripts are for managers
        assertThat(detail.chapters()).extracting(PodcastDtos.Chapter::title).containsExactly("Intro", "Rust");
        assertThat(detail.sources()).singleElement().satisfies(src -> assertThat(src.postId()).isEqualTo(post));
        assertThat(podcasts.transcript(id, listener, "rust")).singleElement().satisfies(seg -> assertThat(seg.startSeconds()).isEqualTo(600.0));
        assertThat(podcasts.transcriptVtt(id, listener)).contains("00:10:00.000 --> 00:50:00.000");

        // Playback state: play count once per listener, resume position kept, completion near the end.
        podcasts.progress(id, listener, 120, false);
        podcasts.progress(id, listener, 240, false);
        assertThat(podcasts.detail(id, listener).playCount()).isEqualTo(1);
        assertThat(podcasts.home(listener).continueListening()).extracting(EpisodeSummary::id).containsExactly(id);
        podcasts.progress(id, listener, 2998, null);
        assertThat(podcasts.detail(id, listener).progress().completed()).isTrue();
        assertThat(podcasts.home(listener).continueListening()).isEmpty();
        assertThat(podcasts.history(listener, null, 10).items()).extracting(EpisodeSummary::id).containsExactly(id);

        // Media can't change while published; the media URL supports byte ranges (seeking).
        assertThatThrownBy(() -> podcasts.uploadMedia(id, owner, mp3(100), 10)).isInstanceOf(ApiException.class);
        String url = podcasts.detail(id, listener).mediaUrl();
        mvc.perform(get(url).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-99/4096"))
                .andExpect(header().string("Content-Type", "audio/mpeg"));

        podcasts.transition(id, owner, "unpublish");
        assertThatThrownBy(() -> podcasts.detail(id, listener)).isInstanceOf(ApiException.class);
        communities.setRole(slug, owner, member.username(), CommunityRole.MODERATOR);
        assertThat(podcasts.studio(slug, member)).extracting(EpisodeSummary::status).containsExactly("ARCHIVED");
        podcasts.delete(id, member);
        mvc.perform(get(url)).andExpect(status().isNotFound()); // media removed with the episode
    }

    @Test
    void privateCommunityEpisodesStayPrivate() {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        AuthUser owner = user("qo" + s), outsider = user("qx" + s);
        String slug = "qpriv" + s;
        communities.create(owner, new CreateCommunityRequest(slug, "Private pods", "", null, "PRIVATE", List.of(), List.of()));
        long id = podcasts.create(slug, owner, new CreateEpisode("Members only", null, "HIGHLIGHTS"));
        podcasts.uploadMedia(id, owner, mp3(512), 600);
        podcasts.replaceTranscript(id, owner, "timestamped", "[00:00] Hi");
        podcasts.transition(id, owner, "submit");
        podcasts.transition(id, owner, "publish");
        assertThatThrownBy(() -> podcasts.detail(id, outsider)).isInstanceOf(ApiException.class);
        assertThat(podcasts.list(null, null, "recent", null, 50, outsider).items()).extracting(EpisodeSummary::id).doesNotContain(id);
        assertThat(podcasts.list(null, null, "recent", null, 50, owner).items()).extracting(EpisodeSummary::id).contains(id);
    }

    @Test
    void validatesScriptCitations() {
        var v = ScriptDrafter.validate("**HOST A:** As one member said [2], and [9] too [1].", 3);
        assertThat(v.text()).isEqualTo("**HOST A:** As one member said [2], and  too [1].");
        assertThat(v.cited()).containsExactly(1, 2);
    }
}
