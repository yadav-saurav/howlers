package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.storage.ImageFixtures;
import app.howlers.storage.ImageVariants;
import app.howlers.storage.StorageService;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import app.howlers.support.TestIds;

/**
 * D-028 (metadata stripped from image originals, private files byte-identical) and D-030 (legacy public DM images
 * moved to private storage, and the compatibility send path no longer creates new public DM images).
 */
@AutoConfigureMockMvc
class PrivacyHardeningIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired StorageService storage;
    @Autowired LegacyDmMedia legacy;

    private record Session(String auth, long id, String username) {}

    private static String sfx() {
        return TestIds.suffix();
    }

    private Session register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@priv.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode b = json.readTree(r.getResponse().getContentAsString());
        return new Session("Bearer " + b.get("accessToken").asText(), b.get("user").get("id").asLong(), name);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private byte[] stored(String key) throws Exception {
        try (InputStream in = storage.get(key).orElseThrow().stream()) {
            return in.readAllBytes();
        }
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    void uploadedImageOriginalsLoseGpsButPrivateFilesStayByteIdentical() throws Exception {
        Session ana = register("pha" + sfx());
        byte[] photo = ImageFixtures.jpegWithGps(1100, 700, 6);
        assertThat(latin1(photo)).contains(ImageFixtures.MAKE);

        // Post image: the stored original and the public URL carry no GPS, camera or XMP data, and still decode.
        JsonNode media = body(mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", photo))
                .header("Authorization", ana.auth())).andExpect(status().isCreated()));
        String url = media.get("url").asText();
        byte[] served = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(latin1(served)).doesNotContain(ImageFixtures.MAKE, ImageFixtures.MODEL, "xmpmeta", "Paris");
        assertThat(ImageVariants.jpegOrientation(served)).isEqualTo(6);
        var img = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(img.getWidth()).isEqualTo(1100);
        assertThat(img.getHeight()).isEqualTo(700);
        assertThat(media.get("width").asInt()).isEqualTo(1100);

        // Avatar (stored as a plain URL) goes through the same path.
        byte[] pngPhoto = ImageFixtures.pngWithGps(200, 120, 1);
        JsonNode me = body(mvc.perform(multipart("/api/v1/users/me/avatar").file(new MockMultipartFile("file", "me.png", "image/png", pngPhoto))
                .header("Authorization", ana.auth())).andExpect(status().isOk()));
        String avatar = me.get("avatarUrl").asText();
        byte[] avatarBytes = mvc.perform(get(avatar)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(latin1(avatarBytes)).doesNotContain(ImageFixtures.MAKE, ImageFixtures.MODEL, "tEXt", "eXIf");
        assertThat(ImageIO.read(new ByteArrayInputStream(avatarBytes)).getWidth()).isEqualTo(200);

        // Private file: the user's own bytes, stored exactly as uploaded (metadata included, by design).
        JsonNode file = body(mvc.perform(multipart("/api/v1/me/files").file(new MockMultipartFile("file", "holiday.jpg", "image/jpeg", photo))
                .header("Authorization", ana.auth())).andExpect(status().isCreated()));
        String key = jdbc.sql("SELECT storage_key FROM user_files WHERE id = ?").param(file.get("id").asLong()).query(String.class).single();
        assertThat(stored(key)).isEqualTo(photo);
        assertThat(file.get("scanEngine").asText()).isEqualTo("STRUCTURAL");
    }

    /** Inserts an image the old way: a public media/ object (+ variant) and its asset row. */
    private long legacyAsset(long ownerId, byte[] bytes, boolean withVariant) {
        String key = "media/legacy" + sfx() + "abcdefgh.png";
        storage.put(key, bytes, "image/png");
        if (withVariant) storage.put(ImageVariants.keyFor(key, 320), bytes, "image/png");
        return jdbc.sql("""
                        INSERT INTO media_assets (owner_id, storage_key, content_type, size_bytes, width, height, variant_widths)
                        VALUES (?, ?, 'image/png', ?, 30, 20, ?) RETURNING id""")
                .params(ownerId, key, bytes.length, withVariant ? "320" : null).query(Long.class).single();
    }

    private String keyOf(long assetId) {
        return jdbc.sql("SELECT storage_key FROM media_assets WHERE id = ?").param(assetId).query(String.class).single();
    }

    private void attachLegacy(long convo, long senderId, long assetId) {
        long msg = jdbc.sql("INSERT INTO dm_messages (conversation_id, sender_id, body) VALUES (?, ?, '') RETURNING id")
                .params(convo, senderId).query(Long.class).single();
        jdbc.sql("INSERT INTO dm_message_media (message_id, media_id, position) VALUES (?, ?, 0)").params(msg, assetId).update();
    }

    @Test
    void legacyDmImagesMoveToPrivateStorageOnceAndPostImagesDont() throws Exception {
        String s = sfx();
        Session ana = register("pla" + s), ben = register("plb" + s), eve = register("ple" + s), boss = register("plx" + s);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = ?").param(boss.id()).update();
        long convo = body(mvc.perform(post("/api/v1/me/conversations").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + ben.username() + "\"}"))
                .andExpect(status().isOk())).get("id").asLong();
        byte[] png = ImageFixtures.pngWithGps(30, 20, 1);

        long dmOnly = legacyAsset(ana.id(), png, true);
        long alsoInPost = legacyAsset(ana.id(), png, false);
        long linkedPublicly = legacyAsset(ana.id(), png, false);
        attachLegacy(convo, ana.id(), dmOnly);
        attachLegacy(convo, ana.id(), alsoInPost);
        attachLegacy(convo, ana.id(), linkedPublicly);

        String slug = "plc" + s;
        mvc.perform(post("/api/v1/communities").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Legacy\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/posts").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"IMAGE\",\"title\":\"gallery\",\"mediaIds\":[" + alsoInPost + "]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/posts").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"LINK\",\"title\":\"look\",\"url\":\"https://howlers.example/api/v1/files/"
                                + keyOf(linkedPublicly) + "\"}"))
                .andExpect(status().isCreated());

        String oldKey = keyOf(dmOnly);
        String oldVariant = ImageVariants.keyFor(oldKey, 320);
        mvc.perform(get("/api/v1/files/" + oldKey)).andExpect(status().isOk());
        JsonNode before = body(mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ben.auth())));
        assertThat(before.get("items").findValues("signed")).anyMatch(n -> !n.asBoolean());

        // The shared test database can hold DM assets from other tests whose bytes were never stored
        // (DirectMessagesIT inserts rows only). Those, and only those, must fail; every other candidate must move.
        long unmovable = jdbc.sql("SELECT a.storage_key FROM media_assets a WHERE " + LegacyDmMedia.IN_DMS_ONLY
                        + " AND NOT " + LegacyDmMedia.PUBLISHED_ELSEWHERE).query(String.class).list()
                .stream().filter(k -> !storage.exists(k)).count();

        // Admin-only trigger; outsiders are refused.
        mvc.perform(post("/api/v1/admin/maintenance/legacy-dm-media").header("Authorization", eve.auth())).andExpect(status().isForbidden());
        JsonNode run = body(mvc.perform(post("/api/v1/admin/maintenance/legacy-dm-media").header("Authorization", boss.auth()))
                .andExpect(status().isOk()));
        assertThat(run.get("moved").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(run.get("skipped").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(run.get("failed").asLong()).isEqualTo(unmovable);
        assertThat(run.get("triggeredBy").asLong()).isEqualTo(boss.id());

        // The DM-only image is private now: old URL and variant 404, bytes and variant moved intact.
        String newKey = keyOf(dmOnly);
        assertThat(newKey).isEqualTo("private/" + oldKey.substring("media/".length()));
        mvc.perform(get("/api/v1/files/" + oldKey)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + oldVariant)).andExpect(status().isNotFound());
        assertThat(storage.exists(oldKey)).isFalse();
        assertThat(stored(newKey)).isEqualTo(png);
        assertThat(storage.exists(ImageVariants.keyFor(newKey, 320))).isTrue();

        // Participants now get signed URLs that work; an outsider can't read it.
        JsonNode items = body(mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ben.auth()))).get("items");
        JsonNode moved = null;
        for (JsonNode m : items) for (JsonNode md : m.get("media")) if (md.get("id").asLong() == dmOnly) moved = md;
        assertThat(moved).isNotNull();
        assertThat(moved.get("signed").asBoolean()).isTrue();
        assertThat(mvc.perform(get(moved.get("url").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(png);
        mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", eve.auth()))
                .andExpect(status().isNotFound());

        // Post images and publicly linked images are untouched.
        assertThat(keyOf(alsoInPost)).startsWith("media/");
        mvc.perform(get("/api/v1/files/" + keyOf(alsoInPost))).andExpect(status().isOk());
        assertThat(keyOf(linkedPublicly)).startsWith("media/");

        // Idempotent: a second run moves nothing, and both runs are recorded.
        JsonNode again = body(mvc.perform(post("/api/v1/admin/maintenance/legacy-dm-media").header("Authorization", boss.auth())));
        assertThat(again.get("moved").asInt()).isZero();
        assertThat(keyOf(dmOnly)).isEqualTo(newKey);
        JsonNode status = body(mvc.perform(get("/api/v1/admin/maintenance").header("Authorization", boss.auth())).andExpect(status().isOk()));
        assertThat(status.get("legacyDmMedia").get("runs").size()).isGreaterThanOrEqualTo(2);
        assertThat(status.get("antivirus").get("engine").asText()).isEqualTo("none");
    }

    @Test
    void sendingAPublicUploadInADmMovesItToPrivateStorage() throws Exception {
        String s = sfx();
        Session ana = register("pda" + s), ben = register("pdb" + s);
        long convo = body(mvc.perform(post("/api/v1/me/conversations").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + ben.username() + "\"}"))
                .andExpect(status().isOk())).get("id").asLong();
        JsonNode up = body(mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file", "p.png", "image/png",
                ImageFixtures.pngWithGps(40, 30, 1))).header("Authorization", ana.auth())).andExpect(status().isCreated()));
        long id = up.get("id").asLong();
        String publicKey = keyOf(id);
        assertThat(publicKey).startsWith("media/");

        JsonNode sent = body(mvc.perform(post("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaIds\":[" + id + "]}")).andExpect(status().isCreated()));
        assertThat(sent.get("media").get(0).get("signed").asBoolean()).isTrue();
        assertThat(keyOf(id)).startsWith("private/");
        mvc.perform(get("/api/v1/files/" + publicKey)).andExpect(status().isNotFound());
        // And it can no longer be put in a public post.
        String slug = "pdc" + s;
        mvc.perform(post("/api/v1/communities").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Compat\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/posts").header("Authorization", ana.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"IMAGE\",\"title\":\"x\",\"mediaIds\":[" + id + "]}"))
                .andExpect(status().isBadRequest());
    }
}
