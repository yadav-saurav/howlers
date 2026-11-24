package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** DM images are private to the conversation (D-024): private storage, participant-signed URLs, no public path. */
@AutoConfigureMockMvc
class DmMediaIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DmMediaUrls urls;

    private record Session(String auth, long id, String username) {}

    private static String sfx() {
        return Long.toString(System.nanoTime() % 10_000_000);
    }

    private Session register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@dmm.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode b = json.readTree(r.getResponse().getContentAsString());
        return new Session("Bearer " + b.get("accessToken").asText(), b.get("user").get("id").asLong(), name);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void dmImagesAreReadableOnlyThroughParticipantGrants() throws Exception {
        String s = sfx();
        Session ana = register("dma" + s), ben = register("dmb" + s), eve = register("dme" + s);
        long convo = body(mvc.perform(post("/api/v1/me/conversations").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + ben.username() + "\"}"))
                .andExpect(status().isOk())).get("id").asLong();

        byte[] bytes = png(1200, 600);
        JsonNode up = body(mvc.perform(multipart("/api/v1/me/dm-media")
                .file(new MockMultipartFile("file", "photo.png", "image/png", bytes)).header("Authorization", ana.auth()))
                .andExpect(status().isCreated()));
        long mediaId = up.get("id").asLong();
        assertThat(up.get("signed").asBoolean()).isTrue();
        assertThat(up.get("url").asText()).startsWith("/api/v1/dm-media/");
        String key = jdbc.sql("SELECT storage_key FROM media_assets WHERE id = ?").param(mediaId).query(String.class).single();
        assertThat(key).startsWith("private/");
        // The private prefix has no public URL, guessable or not, nor do its variants.
        mvc.perform(get("/api/v1/files/" + key)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + key + "?w=320")).andExpect(status().isNotFound());

        // DM images can't be smuggled into a public post.
        String slug = "dmm" + s;
        mvc.perform(post("/api/v1/communities").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"DM media\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/posts").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"IMAGE\",\"title\":\"leak\",\"mediaIds\":[" + mediaId + "]}"))
                .andExpect(status().isBadRequest());

        JsonNode sent = body(mvc.perform(post("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaIds\":[" + mediaId + "]}")).andExpect(status().isCreated()));
        long messageId = sent.get("id").asLong();

        JsonNode benView = body(mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ben.auth()))
                .andExpect(status().isOk())).get("items").get(0).get("media").get(0);
        String benUrl = benView.get("url").asText();
        assertThat(benView.get("signed").asBoolean()).isTrue();
        assertThat(benView.get("expiresAt").isNull()).isFalse();
        assertThat(benView.get("thumbnails").get("w320").asText()).isEqualTo(benUrl + "?w=320");

        // An <img> can't send a token, so the signed URL works without one...
        MvcResult img = mvc.perform(get(benUrl)).andExpect(status().isOk()).andReturn();
        assertThat(img.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(img.getResponse().getHeader("Cache-Control")).contains("private");
        assertThat(img.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        byte[] small = mvc.perform(get(benUrl + "?w=320")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(small)).getWidth()).isEqualTo(320);

        // ...but it can't be minted for an outsider, tampered with, or replayed on the file download route.
        String eveUrl = urls.dto(mediaId, key, 1200, 600, null, eve.id()).url();
        mvc.perform(get(eveUrl)).andExpect(status().isNotFound());
        mvc.perform(get(benUrl.substring(0, benUrl.length() - 2) + "xx")).andExpect(status().isNotFound());
        String grant = benUrl.substring("/api/v1/dm-media/".length());
        mvc.perform(get("/api/v1/downloads/" + grant)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", eve.auth()))
                .andExpect(status().isNotFound());

        // Deleting the message ends the recipient's access at once; the uploader keeps their own image.
        String anaUrl = body(mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ana.auth())))
                .get("items").get(0).get("media").get(0).get("url").asText();
        mvc.perform(delete("/api/v1/me/messages/" + messageId).header("Authorization", ana.auth())).andExpect(status().isNoContent());
        mvc.perform(get(benUrl)).andExpect(status().isNotFound());
        mvc.perform(get(anaUrl)).andExpect(status().isOk());
    }
}
