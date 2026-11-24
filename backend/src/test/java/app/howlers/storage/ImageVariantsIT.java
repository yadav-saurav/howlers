package app.howlers.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import app.howlers.support.TestIds;

/** Uploads get 320/960 px variants beside the original, exposed in DTOs and through {@code ?w=} (D-024). */
@AutoConfigureMockMvc
class ImageVariantsIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@iv.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode upload(String auth, String path, byte[] bytes) throws Exception {
        MvcResult r = mvc.perform(multipart(path).file(new MockMultipartFile("file", "x.png", "image/png", bytes)).header("Authorization", auth))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int widthOf(String url) throws Exception {
        byte[] b = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return ImageIO.read(new ByteArrayInputStream(b)).getWidth();
    }

    @Test
    void postImagesGetVariantsInTheDtoAndOnTheFilesRoute() throws Exception {
        String s = TestIds.suffix();
        String auth = register("iv" + s);

        JsonNode big = upload(auth, "/api/v1/media", ImageVariantsTest.png(1600, 800, false));
        assertThat(big.get("width").asInt()).isEqualTo(1600);
        String url = big.get("url").asText();
        String w320 = big.get("thumbnails").get("w320").asText();
        String w960 = big.get("thumbnails").get("w960").asText();
        assertThat(w320).endsWith("_w320.png");
        assertThat(widthOf(w320)).isEqualTo(320);
        assertThat(widthOf(w960)).isEqualTo(960);
        assertThat(widthOf(url)).isEqualTo(1600);
        assertThat(widthOf(url + "?w=320")).isEqualTo(320); // the same variant, addressed by width

        // A post carries the thumbnails in its media refs.
        String slug = "iv" + s;
        mvc.perform(post("/api/v1/communities").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Variants\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        MvcResult created = mvc.perform(post("/api/v1/posts").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"IMAGE\",\"title\":\"wide\",\"mediaIds\":[" + big.get("id").asLong() + "]}"))
                .andExpect(status().isCreated()).andReturn();
        long postId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        JsonNode media = json.readTree(mvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("media").get(0);
        assertThat(media.get("thumbnails").get("w960").asText()).isEqualTo(w960);

        // Only widths narrower than the original are made; small images have none, and ?w= falls back to the original.
        JsonNode mid = upload(auth, "/api/v1/media", ImageVariantsTest.png(500, 300, false));
        assertThat(mid.get("thumbnails").get("w320").isNull()).isFalse();
        assertThat(mid.get("thumbnails").get("w960").isNull()).isTrue();
        assertThat(widthOf(mid.get("url").asText() + "?w=960")).isEqualTo(500);
        JsonNode small = upload(auth, "/api/v1/media", ImageVariantsTest.png(200, 100, false));
        assertThat(small.get("thumbnails").isNull()).isTrue();
        assertThat(widthOf(small.get("url").asText() + "?w=320")).isEqualTo(200);

        // GIFs keep only the original (animations are never flattened).
        JsonNode gif = upload(auth, "/api/v1/media", ImageVariantsTest.gif(800, 400));
        assertThat(gif.get("thumbnails").isNull()).isTrue();
        mvc.perform(get(gif.get("url").asText() + "?w=320")).andExpect(status().isOk());

        // Avatars are plain URLs; the ?w= form picks up their variant without a DTO change.
        JsonNode me = upload(auth, "/api/v1/users/me/avatar", ImageVariantsTest.png(1000, 1000, true));
        String avatar = me.get("avatarUrl").asText();
        assertThat(widthOf(avatar + "?w=320")).isEqualTo(320);
    }
}
