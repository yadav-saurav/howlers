package app.howlers.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import app.howlers.support.TestIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Chat upgrade: voice notes, replies, per-person favourites and shared themes, in DMs and pack rooms. */
@AutoConfigureMockMvc
class ChatUpgradeIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DmMediaUrls urls;

    private record Session(String auth, long id, String username) {}

    private Session register(String prefix) throws Exception {
        String name = prefix + TestIds.suffix();
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@chat.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode b = json.readTree(r.getResponse().getContentAsString());
        return new Session("Bearer " + b.get("accessToken").asText(), b.get("user").get("id").asLong(), name);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long direct(Session a, Session b) throws Exception {
        return body(mvc.perform(post("/api/v1/me/conversations").header("Authorization", a.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + b.username() + "\"}"))
                .andExpect(status().isOk())).get("id").asLong();
    }

    private long room(Session owner, Session... others) throws Exception {
        String names = String.join(",", Arrays.stream(others).map(o -> "\"" + o.username() + "\"").toList());
        return body(mvc.perform(post("/api/v1/me/rooms").header("Authorization", owner.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Den\",\"usernames\":[" + names + "]}"))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private ResultActions send(Session who, long convo, String payload) throws Exception {
        return mvc.perform(post("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", who.auth())
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private JsonNode messages(Session who, long convo) throws Exception {
        return body(mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", who.auth()))
                .andExpect(status().isOk())).get("items");
    }

    private JsonNode summary(Session who, long convo) throws Exception {
        return body(mvc.perform(get("/api/v1/me/conversations/" + convo).header("Authorization", who.auth()))
                .andExpect(status().isOk()));
    }

    /** A tiny Ogg page header followed by filler: enough for the magic-byte check. */
    private static byte[] ogg() {
        byte[] b = new byte[2048];
        byte[] magic = "OggS".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, b, 0, magic.length);
        for (int i = 4; i < b.length; i++) b[i] = (byte) (i % 251);
        return b;
    }

    private static byte[] webm() {
        byte[] b = new byte[512];
        b[0] = 0x1A; b[1] = 0x45; b[2] = (byte) 0xDF; b[3] = (byte) 0xA3;
        byte[] doc = "B\u0082\u0084webm".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(doc, 0, b, 8, doc.length);
        return b;
    }

    private JsonNode uploadVoice(Session who, byte[] bytes, String type, String durationMs) throws Exception {
        var req = multipart("/api/v1/me/dm-media/voice").file(new MockMultipartFile("file", "voice", type, bytes))
                .header("Authorization", who.auth());
        if (durationMs != null) req.param("durationMs", durationMs);
        return body(mvc.perform(req).andExpect(status().isCreated()));
    }

    @Test
    void voiceNotesArePrivateTypedAndSeekable() throws Exception {
        Session ana = register("vna"), ben = register("vnb"), eve = register("vne");
        long convo = direct(ana, ben);

        byte[] bytes = ogg();
        JsonNode up = uploadVoice(ana, bytes, "audio/ogg;codecs=opus", "4200");
        assertThat(up.get("kind").asText()).isEqualTo("AUDIO");
        assertThat(up.get("contentType").asText()).isEqualTo("audio/ogg");
        assertThat(up.get("durationMs").asInt()).isEqualTo(4200);
        assertThat(up.get("signed").asBoolean()).isTrue();
        assertThat(up.get("thumbnails").isNull()).isTrue();
        long voiceId = up.get("id").asLong();
        String key = jdbc.sql("SELECT storage_key FROM media_assets WHERE id = ?").param(voiceId).query(String.class).single();
        assertThat(key).startsWith("private/").endsWith(".ogg");
        mvc.perform(get("/api/v1/files/" + key)).andExpect(status().isNotFound());

        // WebM is stored with an audio extension so it is served as audio, not video.
        JsonNode web = uploadVoice(ana, webm(), "audio/webm", null);
        assertThat(web.get("contentType").asText()).isEqualTo("audio/webm");
        assertThat(web.get("durationMs").isNull()).isTrue();

        // Type, size and duration are checked; the bytes must match an audio container.
        mvc.perform(multipart("/api/v1/me/dm-media/voice").file(new MockMultipartFile("file", "x", "audio/ogg", "hello world!".getBytes()))
                .header("Authorization", ana.auth())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/me/dm-media/voice").file(new MockMultipartFile("file", "x", "image/png", bytes))
                .header("Authorization", ana.auth())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/me/dm-media/voice").file(new MockMultipartFile("file", "x", "audio/ogg", bytes))
                .param("durationMs", "300001").header("Authorization", ana.auth())).andExpect(status().isBadRequest());
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(bytes, 0, big, 0, 4);
        mvc.perform(multipart("/api/v1/me/dm-media/voice").file(new MockMultipartFile("file", "x", "audio/ogg", big))
                .header("Authorization", ana.auth())).andExpect(status().isBadRequest());

        // Only the uploader can attach it, and it can't travel with images.
        send(ben, convo, "{\"mediaIds\":[" + voiceId + "]}").andExpect(status().isBadRequest());
        long image = body(mvc.perform(multipart("/api/v1/me/dm-media")
                .file(new MockMultipartFile("file", "p.png", "image/png", DmMediaIT.png(40, 40))).header("Authorization", ana.auth()))
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode mixed = body(send(ana, convo, "{\"mediaIds\":[" + voiceId + "," + image + "]}").andExpect(status().isBadRequest()));
        assertThat(mixed.toString()).contains("mediaIds");
        send(ana, convo, "{\"mediaIds\":[" + voiceId + "," + web.get("id").asLong() + "]}").andExpect(status().isBadRequest());

        // A voice note with no body is a message of its own.
        JsonNode sent = body(send(ana, convo, "{\"mediaIds\":[" + voiceId + "]}").andExpect(status().isCreated()));
        assertThat(sent.get("body").asText()).isEmpty();
        assertThat(sent.get("media").get(0).get("kind").asText()).isEqualTo("AUDIO");
        assertThat(summary(ben, convo).get("lastMessage").get("excerpt").asText()).isEqualTo("Sent a voice note");

        JsonNode benMedia = messages(ben, convo).get(0).get("media").get(0);
        assertThat(benMedia.get("kind").asText()).isEqualTo("AUDIO");
        assertThat(benMedia.get("durationMs").asInt()).isEqualTo(4200);
        String url = benMedia.get("url").asText();
        MvcResult full = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        assertThat(full.getResponse().getContentType()).isEqualTo("audio/ogg");
        assertThat(full.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(full.getResponse().getHeader("Accept-Ranges")).isEqualTo("bytes");

        MvcResult part = mvc.perform(get(url).header("Range", "bytes=0-3")).andExpect(status().isPartialContent()).andReturn();
        assertThat(part.getResponse().getContentAsByteArray()).isEqualTo("OggS".getBytes(StandardCharsets.US_ASCII));
        assertThat(part.getResponse().getHeader("Content-Range")).isEqualTo("bytes 0-3/" + bytes.length);

        // Outsiders get nothing, not even through a grant minted for them.
        mvc.perform(get("/api/v1/me/conversations/" + convo + "/messages").header("Authorization", eve.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(get(urls.dto(voiceId, key, null, null, null, eve.id()).url())).andExpect(status().isNotFound());
        mvc.perform(get(urls.dto(voiceId, key, null, null, null, eve.id()).url()).header("Range", "bytes=0-3"))
                .andExpect(status().isNotFound());
    }

    @Test
    void repliesStayInTheirConversationAndShowAPreview() throws Exception {
        Session ana = register("rpa"), ben = register("rpb"), cat = register("rpc");
        long convo = direct(ana, ben);
        long other = direct(ana, cat);
        long original = body(send(ben, convo, "{\"body\":\"Are we still on for the hike tomorrow morning?\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        long elsewhere = body(send(cat, other, "{\"body\":\"elsewhere\"}")).get("id").asLong();

        JsonNode bad = body(send(ana, convo, "{\"body\":\"yes\",\"replyToId\":" + elsewhere + "}").andExpect(status().isBadRequest()));
        assertThat(bad.toString()).contains("replyToId");
        send(ana, convo, "{\"body\":\"yes\",\"replyToId\":999999999}").andExpect(status().isBadRequest());

        JsonNode reply = body(send(ana, convo, "{\"body\":\"Yes!\",\"replyToId\":" + original + "}").andExpect(status().isCreated()));
        JsonNode preview = reply.get("replyTo");
        assertThat(preview.get("id").asLong()).isEqualTo(original);
        assertThat(preview.get("senderId").asLong()).isEqualTo(ben.id());
        assertThat(preview.get("excerpt").asText()).isEqualTo("Are we still on for the hike tomorrow morning?");
        assertThat(preview.get("deleted").asBoolean()).isFalse();
        assertThat(preview.get("mediaKind").isNull()).isTrue();

        JsonNode list = messages(ben, convo);
        assertThat(list.get(0).get("replyTo").isNull()).isTrue();
        assertThat(list.get(1).get("replyTo").get("id").asLong()).isEqualTo(original);

        // Long bodies are cut to an excerpt; image-only originals report their media kind.
        String longBody = "word ".repeat(60);
        long longId = body(send(ben, convo, "{\"body\":\"" + longBody + "\"}")).get("id").asLong();
        assertThat(body(send(ana, convo, "{\"body\":\"ok\",\"replyToId\":" + longId + "}")).get("replyTo").get("excerpt").asText())
                .hasSizeLessThanOrEqualTo(120).endsWith("…");
        long image = body(mvc.perform(multipart("/api/v1/me/dm-media")
                .file(new MockMultipartFile("file", "p.png", "image/png", DmMediaIT.png(40, 40))).header("Authorization", ben.auth()))
                .andExpect(status().isCreated())).get("id").asLong();
        long imageMsg = body(send(ben, convo, "{\"mediaIds\":[" + image + "]}")).get("id").asLong();
        JsonNode toImage = body(send(ana, convo, "{\"body\":\"nice\",\"replyToId\":" + imageMsg + "}")).get("replyTo");
        assertThat(toImage.get("mediaKind").asText()).isEqualTo("IMAGE");
        assertThat(toImage.get("excerpt").asText()).isEmpty();

        // Deleting the original keeps the reply, with a "deleted" preview and no text; replying to it is still allowed.
        mvc.perform(delete("/api/v1/me/messages/" + original).header("Authorization", ben.auth())).andExpect(status().isNoContent());
        JsonNode after = messages(ana, convo);
        JsonNode stale = null;
        for (JsonNode m : after) if (m.get("id").asLong() == reply.get("id").asLong()) stale = m.get("replyTo");
        assertThat(stale).isNotNull();
        assertThat(stale.get("deleted").asBoolean()).isTrue();
        assertThat(stale.get("excerpt").asText()).isEmpty();
        assertThat(body(send(ana, convo, "{\"body\":\"too late\",\"replyToId\":" + original + "}").andExpect(status().isCreated()))
                .get("replyTo").get("deleted").asBoolean()).isTrue();
    }

    @Test
    void favouritesArePerPerson() throws Exception {
        Session ana = register("fva"), ben = register("fvb"), eve = register("fve");
        long convo = direct(ana, ben);
        send(ana, convo, "{\"body\":\"hi\"}").andExpect(status().isCreated());
        assertThat(summary(ana, convo).get("favorite").asBoolean()).isFalse();

        mvc.perform(put("/api/v1/me/conversations/" + convo + "/favorite").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}")).andExpect(status().isNoContent());
        assertThat(summary(ana, convo).get("favorite").asBoolean()).isTrue();
        assertThat(summary(ben, convo).get("favorite").asBoolean()).isFalse();
        JsonNode inbox = body(mvc.perform(get("/api/v1/me/conversations").header("Authorization", ana.auth())));
        assertThat(inbox.get(0).get("favorite").asBoolean()).isTrue();

        mvc.perform(put("/api/v1/me/conversations/" + convo + "/favorite").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":false}")).andExpect(status().isNoContent());
        assertThat(summary(ana, convo).get("favorite").asBoolean()).isFalse();

        mvc.perform(put("/api/v1/me/conversations/" + convo + "/favorite").header("Authorization", eve.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/me/conversations/" + convo + "/favorite").header("Authorization", ana.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    private ResultActions theme(Session who, long convo, String payload) throws Exception {
        return mvc.perform(put("/api/v1/me/conversations/" + convo + "/theme").header("Authorization", who.auth())
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    @Test
    void themesAreSharedAndAnnouncedInTheTimeline() throws Exception {
        Session ana = register("tha"), ben = register("thb"), eve = register("the");
        long convo = direct(ana, ben);
        send(ben, convo, "{\"body\":\"hi\"}").andExpect(status().isCreated());
        assertThat(summary(ana, convo).get("theme").isNull()).isTrue();

        JsonNode set = body(theme(ana, convo, "{\"theme\":\"sunset\"}").andExpect(status().isOk()));
        assertThat(set.get("id").asLong()).isEqualTo(convo);
        assertThat(set.get("theme").asText()).isEqualTo("sunset");
        assertThat(summary(ben, convo).get("theme").asText()).isEqualTo("sunset");

        JsonNode last = messages(ben, convo).get(1);
        assertThat(last.get("kind").asText()).isEqualTo("SYSTEM");
        assertThat(last.get("body").asText()).isEqualTo(ana.username() + " changed the chat theme to Golden Hour");
        assertThat(jdbc.sql("SELECT system_event FROM dm_messages WHERE id = ?").param(last.get("id").asLong())
                .query(String.class).single()).isEqualTo("THEME");
        // A theme line is never "unread", and its inbox preview has no sender (so it never reads "You: ...").
        assertThat(summary(ben, convo).get("unreadCount").asLong()).isZero();
        assertThat(summary(ana, convo).get("lastMessage").get("senderId").isNull()).isTrue();

        JsonNode invalid = body(theme(ana, convo, "{\"theme\":\"rainbow\"}").andExpect(status().isBadRequest()));
        assertThat(invalid.toString()).contains("theme");
        theme(eve, convo, "{\"theme\":\"ocean\"}").andExpect(status().isNotFound());

        // Setting the same theme again changes nothing.
        theme(ben, convo, "{\"theme\":\"sunset\"}").andExpect(status().isOk());
        assertThat(messages(ben, convo)).hasSize(2);

        JsonNode reset = body(theme(ben, convo, "{\"theme\":\"\"}").andExpect(status().isOk()));
        assertThat(reset.get("theme").isNull()).isTrue();
        assertThat(messages(ana, convo).get(2).get("body").asText()).isEqualTo(ben.username() + " reset the chat theme");
        theme(ben, convo, "{\"theme\":\"ember\"}").andExpect(status().isOk());
        assertThat(body(theme(ben, convo, "{\"theme\":null}").andExpect(status().isOk())).get("theme").isNull()).isTrue();
    }

    @Test
    void packRoomsGetEveryFeature() throws Exception {
        Session mira = register("rma"), dev = register("rmb"), sol = register("rmc"), eve = register("rme");
        long room = room(mira, dev, sol);

        // Voice note and reply.
        long voice = uploadVoice(dev, ogg(), "audio/ogg", "1500").get("id").asLong();
        JsonNode note = body(send(dev, room, "{\"mediaIds\":[" + voice + "]}").andExpect(status().isCreated()));
        JsonNode reply = body(send(sol, room, "{\"body\":\"love it\",\"replyToId\":" + note.get("id").asLong() + "}")
                .andExpect(status().isCreated()));
        assertThat(reply.get("replyTo").get("mediaKind").asText()).isEqualTo("AUDIO");
        assertThat(reply.get("replyTo").get("senderId").asLong()).isEqualTo(dev.id());
        String miraUrl = messages(mira, room).get(1).get("media").get(0).get("url").asText();
        mvc.perform(get(miraUrl).header("Range", "bytes=2-5")).andExpect(status().isPartialContent());
        assertThat(summary(mira, room).get("lastMessage").get("excerpt").asText()).isEqualTo(sol.username() + ": love it");

        // A reply can't reach into a conversation the room doesn't own.
        long direct = direct(mira, eve);
        long outside = body(send(eve, direct, "{\"body\":\"psst\"}")).get("id").asLong();
        send(mira, room, "{\"body\":\"x\",\"replyToId\":" + outside + "}").andExpect(status().isBadRequest());

        // Favourites are per member; the theme is shared and announced.
        mvc.perform(put("/api/v1/me/conversations/" + room + "/favorite").header("Authorization", sol.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}")).andExpect(status().isNoContent());
        assertThat(summary(sol, room).get("favorite").asBoolean()).isTrue();
        assertThat(summary(dev, room).get("favorite").asBoolean()).isFalse();

        JsonNode themed = body(theme(dev, room, "{\"theme\":\"aurora\"}").andExpect(status().isOk()));
        assertThat(themed.get("kind").asText()).isEqualTo("ROOM");
        assertThat(themed.get("theme").asText()).isEqualTo("aurora");
        assertThat(summary(mira, room).get("theme").asText()).isEqualTo("aurora");
        assertThat(summary(mira, room).get("lastMessage").get("excerpt").asText())
                .isEqualTo(dev.username() + " changed the chat theme to Northern Lights");
        theme(eve, room, "{\"theme\":\"neon\"}").andExpect(status().isNotFound());
        theme(dev, room, "{\"theme\":\"Aurora!\"}").andExpect(status().isBadRequest());
    }
}
