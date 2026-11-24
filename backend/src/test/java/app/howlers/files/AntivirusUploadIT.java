package app.howlers.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.storage.ImageFixtures;
import app.howlers.support.FakeClamd;
import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** D-029 end to end: uploads go through the structural checks and then a (fake) clamd over the real INSTREAM client. */
@AutoConfigureMockMvc
class AntivirusUploadIT extends IntegrationTest {
    static final FakeClamd CLAMD;

    static {
        try {
            CLAMD = new FakeClamd();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void clamav(DynamicPropertyRegistry r) {
        r.add("howlers.files.antivirus", () -> "clamav");
        r.add("howlers.files.antivirus-required", () -> "false");
        r.add("howlers.files.clamav.host", () -> "127.0.0.1");
        r.add("howlers.files.clamav.port", CLAMD::port);
        r.add("howlers.files.clamav.read-timeout", () -> "PT2S");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @AfterEach
    void reset() {
        CLAMD.mode(FakeClamd.Mode.NORMAL);
    }

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@av.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private ResultActions upload(String auth, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/me/files").file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .header("Authorization", auth));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void cleanFilesAreLabelledScannedAndInfectedOnesRefused() throws Exception {
        String auth = register("avu" + System.nanoTime() % 1_000_000);
        byte[] notes = "meeting notes, nothing to see".getBytes(StandardCharsets.UTF_8);
        int before = CLAMD.payloads().size();
        JsonNode clean = body(upload(auth, "notes.txt", notes).andExpect(status().isCreated()));
        assertThat(clean.get("scanEngine").asText()).isEqualTo("CLAMAV");
        assertThat(clean.get("scanDetail").asText()).startsWith("Scanned by ClamAV");
        assertThat(CLAMD.payloads()).hasSize(before + 1);
        assertThat(CLAMD.payloads().get(before)).isEqualTo(notes); // the whole stored file was streamed

        JsonNode refused = body(upload(auth, "readme.txt", ("hello " + FakeClamd.MARKER).getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest()));
        assertThat(refused.get("fieldErrors").get("file").asText()).contains("ClamAV flagged").contains("Eicar-Signature");
        JsonNode list = body(mvc.perform(get("/api/v1/me/files").header("Authorization", auth)));
        assertThat(list.get("items").size()).isEqualTo(1); // nothing stored for the refused upload

        // Share metadata tells recipients which check ran.
        long id = clean.get("id").asLong();
        JsonNode link = body(mvc.perform(post("/api/v1/me/files/" + id + "/links").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated()));
        String token = link.get("token").asText();
        assertThat(body(mvc.perform(get("/api/v1/share/" + token))).get("scanEngine").asText()).isEqualTo("CLAMAV");

        // E2EE ciphertext is never sent to clamd.
        int beforeE2ee = CLAMD.payloads().size();
        byte[] container = new byte[64];
        System.arraycopy(new byte[]{'S', 'T', 'R', 'A', 'T', 'A', 'E', '2', 1}, 0, container, 0, 9);
        mvc.perform(multipart("/api/v1/me/files").file(new MockMultipartFile("file", "blob.bin", "application/octet-stream", container))
                .param("e2ee", "true").param("label", "secret").header("Authorization", auth)).andExpect(status().isCreated());
        assertThat(CLAMD.payloads()).hasSize(beforeE2ee);
    }

    @Test
    void unreachableClamdFallsBackToStructuralChecksWhenNotRequired() throws Exception {
        String auth = register("avd" + System.nanoTime() % 1_000_000);
        CLAMD.mode(FakeClamd.Mode.CLOSE_IMMEDIATELY);
        JsonNode f = body(upload(auth, "notes.txt", "plain".getBytes(StandardCharsets.UTF_8)).andExpect(status().isCreated()));
        assertThat(f.get("scanEngine").asText()).isEqualTo("STRUCTURAL");
        assertThat(f.get("scanDetail").asText()).contains("Not scanned for malware");
    }

    @Test
    void imagesAreScannedAfterTheirMetadataIsStripped() throws Exception {
        String auth = register("avi" + System.nanoTime() % 1_000_000);
        int before = CLAMD.payloads().size();
        mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file", "p.png", "image/png", ImageFixtures.pngWithGps(40, 30, 1)))
                .header("Authorization", auth)).andExpect(status().isCreated());
        assertThat(CLAMD.payloads()).hasSize(before + 1);
        assertThat(new String(CLAMD.payloads().get(before), StandardCharsets.ISO_8859_1)).doesNotContain(ImageFixtures.MODEL);
    }
}
