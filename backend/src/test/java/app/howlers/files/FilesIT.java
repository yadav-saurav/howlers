package app.howlers.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Private files, share links and E2EE storage, exercised over HTTP so the security rules are part of the test. */
@AutoConfigureMockMvc
class FilesIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    private static String sfx() {
        return Long.toString(System.nanoTime() % 10_000_000);
    }

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@f.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static byte[] pdf(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 9);
        return b;
    }

    private ResultActions upload(String auth, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/me/files").file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .header("Authorization", auth));
    }

    private long uploadOk(String auth, String name, byte[] bytes) throws Exception {
        return body(upload(auth, name, bytes).andExpect(status().isCreated())).get("id").asLong();
    }

    private JsonNode createLink(String auth, long fileId, String jsonBody) throws Exception {
        return body(mvc.perform(post("/api/v1/me/files/" + fileId + "/links").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody)).andExpect(status().isCreated()));
    }

    private ResultActions unlock(String token, String password, String auth) throws Exception {
        var req = post("/api/v1/share/" + token + "/unlock").contentType(MediaType.APPLICATION_JSON)
                .content(password == null ? "{}" : "{\"password\":\"" + password + "\"}");
        if (auth != null) req = req.header("Authorization", auth);
        return mvc.perform(req);
    }

    private List<String> events(String auth, long fileId, long linkId) throws Exception {
        JsonNode items = body(mvc.perform(get("/api/v1/me/files/" + fileId + "/links/" + linkId + "/access").header("Authorization", auth))
                .andExpect(status().isOk())).get("items");
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.get("event").asText()));
        return out;
    }

    @Test
    void filesArePrivateToTheirOwner() throws Exception {
        String s = sfx();
        String alice = register("fa" + s), bob = register("fb" + s);
        byte[] bytes = pdf(5000);
        JsonNode created = body(upload(alice, "../../Quarterly report.pdf", bytes).andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        assertThat(created.get("name").asText()).isEqualTo("Quarterly report.pdf"); // path stripped
        assertThat(created.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(created.get("scanStatus").asText()).isEqualTo("CLEAN");
        assertThat(created.get("sha256").asText()).hasSize(64);

        // Nobody else can see, read, rename, delete or share it; it isn't confirmed to exist (404, not 403).
        mvc.perform(get("/api/v1/me/files/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/files/" + id + "/content").header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/files/" + id + "/download-url").header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/me/files/" + id).header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mine.pdf\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/files/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/files/" + id + "/links").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/files/" + id + "/links").header("Authorization", bob)).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/api/v1/me/files").header("Authorization", bob))).get("items")).isEmpty();
        mvc.perform(get("/api/v1/me/files")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/files/" + id + "/content")).andExpect(status().isUnauthorized());
        // The public media path never serves the private prefix.
        String key = jdbc.sql("SELECT storage_key FROM user_files WHERE id = ?").param(id).query(String.class).single();
        assertThat(key).startsWith("private/");
        mvc.perform(get("/api/v1/files/" + key)).andExpect(status().isNotFound());

        // The owner downloads it as an attachment, with nosniff and Range support.
        mvc.perform(get("/api/v1/me/files/" + id + "/content").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/api/v1/me/files/" + id + "/content").header("Authorization", alice).header("Range", "bytes=0-8"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-8/5000"));
        // ... or through a short-lived signed URL for plain browser downloads.
        String url = body(mvc.perform(post("/api/v1/me/files/" + id + "/download-url").header("Authorization", alice))).get("url").asText();
        byte[] got = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(bytes);
        mvc.perform(get(url.substring(0, url.length() - 3) + "abc")).andExpect(status().isNotFound()); // tampered signature

        // Rename (extension must stay consistent with the checked content) and search by name.
        mvc.perform(patch("/api/v1/me/files/" + id).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Q3 board pack.pdf\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Q3 board pack.pdf"));
        mvc.perform(patch("/api/v1/me/files/" + id).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Q3 board pack.html\"}")).andExpect(status().isBadRequest());
        uploadOk(alice, "notes.txt", "hello world\n".getBytes(StandardCharsets.UTF_8));
        JsonNode found = body(mvc.perform(get("/api/v1/me/files?q=board").header("Authorization", alice))).get("items");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("id").asLong()).isEqualTo(id);
        assertThat(body(mvc.perform(get("/api/v1/me/files?q=%25").header("Authorization", alice))).get("items")).isEmpty(); // LIKE escaped

        JsonNode usage = body(mvc.perform(get("/api/v1/me/files/usage").header("Authorization", alice)));
        assertThat(usage.get("usedBytes").asLong()).isEqualTo(5000 + 12);
        assertThat(usage.get("fileCount").asLong()).isEqualTo(2);

        // Delete: gone from the library, usage drops, the stored bytes are removed, live links stop working.
        String token = createLink(alice, id, "{}").get("token").asText();
        mvc.perform(delete("/api/v1/me/files/" + id).header("Authorization", alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/files/" + id).header("Authorization", alice)).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/api/v1/me/files/usage").header("Authorization", alice))).get("usedBytes").asLong()).isEqualTo(12);
        mvc.perform(get("/api/v1/share/" + token)).andExpect(status().isGone());
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM user_files WHERE id = ?").param(id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void rejectsDangerousOrMislabelledFilesAndEnforcesQuota() throws Exception {
        String s = sfx();
        String u = register("fr" + s);
        byte[] exe = new byte[200];
        exe[0] = 'M'; exe[1] = 'Z';
        upload(u, "invoice.pdf", exe).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(org.hamcrest.Matchers.containsString("Windows executable")));
        upload(u, "setup.exe", pdf(100)).andExpect(status().isBadRequest());
        upload(u, "deploy.sh", "#!/bin/sh\nrm -rf /\n".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(u, "readme.txt", "#!/usr/bin/env python\nprint(1)\n".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        upload(u, "photo.pdf", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(org.hamcrest.Matchers.containsString("doesn't match")));
        upload(u, "mystery.dat", new byte[]{0, 1, 2, 3, 4, 5, (byte) 0xFE}).andExpect(status().isBadRequest());
        upload(u, "macro.docm", zip("word/document.xml", "<w/>".getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        upload(u, "tools.zip", zip("payload/setup.exe", new byte[]{'M', 'Z', 0})).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(org.hamcrest.Matchers.containsString("executable")));
        upload(u, "bomb.zip", zipBomb()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(org.hamcrest.Matchers.containsString("decompression bomb")));
        upload(u, "too-big.pdf", pdf(2 * 1024 * 1024 + 1)).andExpect(status().isBadRequest()); // test max-file-mb = 2
        assertThat(jdbc.sql("SELECT count(*) FROM user_files f JOIN users x ON x.id = f.owner_id WHERE x.username = ?")
                .param("fr" + s).query(Long.class).single()).isZero(); // nothing rejected was kept

        // Accepted: a real archive, Office-style zip, text formats; active content is still only ever an attachment.
        uploadOk(u, "photos.zip", zip("a.txt", "hi".getBytes(StandardCharsets.UTF_8)));
        JsonNode docx = body(upload(u, "letter.docx", zip("word/document.xml", "<w/>".getBytes(StandardCharsets.UTF_8))).andExpect(status().isCreated()));
        assertThat(docx.get("contentType").asText()).contains("wordprocessingml");
        long html = uploadOk(u, "page.html", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/me/files/" + html + "/content").header("Authorization", u))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox")));

        // Quota (test quota-mb = 4): 2 MB + 2 MB fits, nothing more does. Deleting frees space.
        long big1 = uploadOk(u, "one.pdf", pdf(2 * 1024 * 1024 - 4096));
        uploadOk(u, "two.pdf", pdf(2 * 1024 * 1024 - 4096));
        upload(u, "three.pdf", pdf(20_000)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.file").value(org.hamcrest.Matchers.containsString("quota")));
        mvc.perform(delete("/api/v1/me/files/" + big1).header("Authorization", u)).andExpect(status().isNoContent());
        uploadOk(u, "three.pdf", pdf(20_000));
    }

    @Test
    void shareLinksEnforcePasswordLimitExpiryRevocationAndLogAccess() throws Exception {
        String s = sfx();
        String owner = register("fo" + s), other = register("fx" + s);
        byte[] bytes = pdf(3000);
        long id = uploadOk(owner, "contract.pdf", bytes);

        JsonNode created = createLink(owner, id, "{\"password\":\"open sesame\",\"maxDownloads\":2,\"expiresAt\":\""
                + Instant.now().plus(2, ChronoUnit.DAYS) + "\"}");
        String token = created.get("token").asText();
        long linkId = created.get("link").get("id").asLong();
        assertThat(created.get("link").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("link").get("hasPassword").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT password_hash FROM file_share_links WHERE id = ?").param(linkId).query(String.class).single())
                .doesNotContain("open sesame").startsWith("$2"); // bcrypt
        assertThat(jdbc.sql("SELECT count(*) FROM file_share_links WHERE token_hash = ?").param(token).query(Long.class).single()).isZero();

        // Signed-out visitors see metadata, then must pass the password.
        mvc.perform(get("/api/v1/share/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("contract.pdf"))
                .andExpect(jsonPath("$.requiresPassword").value(true))
                .andExpect(jsonPath("$.downloadsRemaining").value(2));
        mvc.perform(get("/api/v1/share/not-a-real-token-123456789")).andExpect(status().isNotFound());
        unlock(token, null, null).andExpect(status().isBadRequest());
        unlock(token, "wrong", null).andExpect(status().isBadRequest());
        String url = body(unlock(token, "open sesame", other).andExpect(status().isOk())).get("downloadUrl").asText();
        byte[] got = mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("contract.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(bytes);
        // Limited links ignore Range (every request is a counted, full download).
        mvc.perform(get(url).header("Range", "bytes=100-")).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isGone());
        unlock(token, "open sesame", null).andExpect(status().isGone());
        mvc.perform(get("/api/v1/share/" + token)).andExpect(status().isGone());

        JsonNode links = body(mvc.perform(get("/api/v1/me/files/" + id + "/links").header("Authorization", owner)));
        assertThat(links.get(0).get("status").asText()).isEqualTo("EXHAUSTED");
        assertThat(links.get(0).get("downloadCount").asInt()).isEqualTo(2);
        List<String> ev = events(owner, id, linkId);
        assertThat(ev).containsExactly("LIMIT_REACHED", "LIMIT_REACHED", "LIMIT_REACHED", "DOWNLOADED", "DOWNLOADED",
                "WRONG_PASSWORD", "WRONG_PASSWORD", "VIEWED");
        JsonNode log = body(mvc.perform(get("/api/v1/me/files/" + id + "/links/" + linkId + "/access").header("Authorization", owner))).get("items");
        JsonNode firstDownload = log.get(4);
        assertThat(firstDownload.get("viewer").get("username").asText()).isEqualTo("fx" + s); // signed-in viewer is named
        assertThat(log.get(7).get("viewer").isNull()).isTrue();                              // anonymous view
        assertThat(log.get(7).get("visitor").asText()).hasSize(12).doesNotContain("127.0.0.1");
        assertThat(jdbc.sql("SELECT count(*) FROM file_share_access WHERE link_id = ? AND visitor_hash LIKE '%127%0%0%1%'")
                .param(linkId).query(Long.class).single()).isZero();
        // Other people can't read the log.
        mvc.perform(get("/api/v1/me/files/" + id + "/links/" + linkId + "/access").header("Authorization", other)).andExpect(status().isNotFound());

        // Expiry.
        JsonNode open = createLink(owner, id, "{}");
        String openToken = open.get("token").asText();
        long openId = open.get("link").get("id").asLong();
        String openUrl = body(unlock(openToken, null, null).andExpect(status().isOk())).get("downloadUrl").asText();
        mvc.perform(get(openUrl).header("Range", "bytes=0-9")).andExpect(status().isPartialContent()); // unlimited links support Range
        jdbc.sql("UPDATE file_share_links SET expires_at = now() - interval '1 minute' WHERE id = ?").param(openId).update();
        mvc.perform(get("/api/v1/share/" + openToken)).andExpect(status().isGone()).andExpect(jsonPath("$.message").value("This link has expired."));
        mvc.perform(get(openUrl)).andExpect(status().isGone()); // a grant issued earlier doesn't outlive the link
        mvc.perform(post("/api/v1/me/files/" + id + "/links").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresAt\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}")).andExpect(status().isBadRequest());

        // Revocation, including for a grant obtained before the revoke; others can't revoke.
        JsonNode r = createLink(owner, id, "{}");
        String rToken = r.get("token").asText();
        long rId = r.get("link").get("id").asLong();
        String rUrl = body(unlock(rToken, null, null)).get("downloadUrl").asText();
        mvc.perform(delete("/api/v1/me/files/" + id + "/links/" + rId).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/me/files/" + id + "/links/" + rId).header("Authorization", owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/share/" + rToken)).andExpect(status().isGone());
        mvc.perform(get(rUrl)).andExpect(status().isGone());
        assertThat(events(owner, id, rId)).containsExactly("REVOKED", "REVOKED");

        // Wrong-password lockout: after 5 failures from one visitor, even the right password is refused for a while.
        JsonNode p = createLink(owner, id, "{\"password\":\"right-one\"}");
        String pToken = p.get("token").asText();
        for (int i = 0; i < ShareService.MAX_FAILURES_PER_VISITOR; i++) unlock(pToken, "guess" + i, null).andExpect(status().isBadRequest());
        unlock(pToken, "right-one", null).andExpect(status().isTooManyRequests());
        assertThat(events(owner, id, p.get("link").get("id").asLong())).first().isEqualTo("LOCKED");
    }

    @Test
    void endToEndEncryptedFilesStoreOnlyCiphertext() throws Exception {
        String s = sfx();
        String u = register("fe" + s);
        byte[] notEncrypted = pdf(500);
        mvc.perform(multipart("/api/v1/me/files").file(new MockMultipartFile("file", "x.pdf", "application/pdf", notEncrypted))
                .param("e2ee", "true").header("Authorization", u)).andExpect(status().isBadRequest());

        byte[] container = new byte[400];
        new java.util.Random(7).nextBytes(container);
        System.arraycopy(DefaultFileScanner.E2EE_MAGIC, 0, container, 0, DefaultFileScanner.E2EE_MAGIC.length);
        JsonNode f = body(mvc.perform(multipart("/api/v1/me/files")
                        .file(new MockMultipartFile("file", "secret-plans.exe", "application/x-msdownload", container))
                        .param("e2ee", "true").header("Authorization", u))
                .andExpect(status().isCreated()));
        long id = f.get("id").asLong();
        assertThat(f.get("e2ee").asBoolean()).isTrue();
        assertThat(f.get("scanStatus").asText()).isEqualTo("UNSCANNED");
        assertThat(f.get("contentType").asText()).isEqualTo("application/octet-stream");
        assertThat(f.get("name").asText()).isEqualTo("Encrypted file"); // the client's file name is never stored

        // E2EE links must say where the key is; passphrase links carry only the wrapped key and KDF parameters.
        mvc.perform(post("/api/v1/me/files/" + id + "/links").header("Authorization", u).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        String wrapped = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[60]);
        String salt = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        mvc.perform(post("/api/v1/me/files/" + id + "/links").header("Authorization", u).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyMode\":\"PASSPHRASE\",\"wrappedKey\":{\"wrappedKey\":\"" + wrapped + "\",\"salt\":\"" + salt
                        + "\",\"iterations\":1000}}")).andExpect(status().isBadRequest()); // < 600k iterations
        String token = createLink(u, id, "{\"keyMode\":\"PASSPHRASE\",\"wrappedKey\":{\"wrappedKey\":\"" + wrapped + "\",\"salt\":\""
                + salt + "\",\"iterations\":600000}}").get("token").asText();
        mvc.perform(get("/api/v1/share/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.e2ee").value(true))
                .andExpect(jsonPath("$.fileName").doesNotExist())
                .andExpect(jsonPath("$.keyMode").value("PASSPHRASE"))
                .andExpect(jsonPath("$.scanStatus").value("UNSCANNED"));
        JsonNode un = body(unlock(token, null, null).andExpect(status().isOk()));
        assertThat(un.get("wrappedKey").get("iterations").asInt()).isEqualTo(600000);
        byte[] got = mvc.perform(get(un.get("downloadUrl").asText())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(container);

        String fragmentToken = createLink(u, id, "{\"keyMode\":\"FRAGMENT\"}").get("token").asText();
        assertThat(body(unlock(fragmentToken, null, null)).get("wrappedKey").isNull()).isTrue();

        // Plain files can't take key modes.
        long plain = uploadOk(u, "a.pdf", pdf(100));
        mvc.perform(post("/api/v1/me/files/" + plain + "/links").header("Authorization", u).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyMode\":\"FRAGMENT\"}")).andExpect(status().isBadRequest());
    }

    private static byte[] zip(String entry, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(content);
            z.closeEntry();
        }
        return out.toByteArray();
    }

    /** ~150 MB of zeros, compressing to roughly 150 KB. */
    private static byte[] zipBomb() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] zeros = new byte[1024 * 1024];
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.setLevel(9);
            z.putNextEntry(new ZipEntry("zeros.bin.txt"));
            for (int i = 0; i < 150; i++) z.write(zeros);
            z.closeEntry();
        }
        return out.toByteArray();
    }
}
