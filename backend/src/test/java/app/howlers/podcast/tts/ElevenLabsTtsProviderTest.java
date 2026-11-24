package app.howlers.podcast.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.podcast.tts.TtsProvider.Speaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The ElevenLabs request/response contract against a loopback HTTP server: nothing leaves the machine and no key is real.
 */
class ElevenLabsTtsProviderTest {
    private static final String KEY = "sk_test_never_real_0123456789";
    private HttpServer server;
    private final ConcurrentLinkedQueue<Map<String, Object>> seen = new ConcurrentLinkedQueue<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code statuses} in order (then the last one forever); 200 answers carry a silent MP3. */
    private ElevenLabsTtsProvider provider(int... statuses) throws Exception {
        AtomicInteger n = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.add(Map.of("method", ex.getRequestMethod(), "uri", ex.getRequestURI().toString(),
                    "key", String.valueOf(ex.getRequestHeaders().getFirst("xi-api-key")),
                    "accept", String.valueOf(ex.getRequestHeaders().getFirst("Accept")),
                    "body", new String(body, StandardCharsets.UTF_8)));
            int status = statuses[Math.min(n.getAndIncrement(), statuses.length - 1)];
            byte[] out = status == 200 ? Mp3.silence44k(5)
                    : ("{\"detail\":{\"status\":\"" + (status == 401 ? "invalid_api_key" : "too_many_concurrent_requests")
                    + "\",\"message\":\"echo " + KEY + "\"}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", status == 200 ? "audio/mpeg" : "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        TtsProperties.ElevenLabs cfg = new TtsProperties(null, null, null, null, null, new TtsProperties.ElevenLabs(KEY,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "eleven_multilingual_v2", null, null, null, "voiceBbb", "Bea",
                Duration.ofSeconds(5))).eleven();
        return new ElevenLabsTtsProvider(cfg, HttpClient.newHttpClient(), 1);
    }

    @Test
    void sendsTheDocumentedRequestAndReturnsMp3() throws Exception {
        ElevenLabsTtsProvider p = provider(200);
        byte[] audio = p.synthesize("Hello \"there\".", Speaker.A);
        assertThat(Mp3.parse(audio).frameCount()).isEqualTo(5);
        Map<String, Object> r = seen.peek();
        assertThat(r.get("method")).isEqualTo("POST");
        assertThat(r.get("uri")).isEqualTo("/v1/text-to-speech/" + TtsProperties.DEFAULT_VOICE_A + "?output_format=mp3_44100_128");
        assertThat(r.get("key")).isEqualTo(KEY);
        assertThat(r.get("accept")).isEqualTo("audio/mpeg");
        JsonNode body = new ObjectMapper().readTree((String) r.get("body"));
        assertThat(body.get("text").asText()).isEqualTo("Hello \"there\".");
        assertThat(body.get("model_id").asText()).isEqualTo("eleven_multilingual_v2");

        p.synthesize("Second host.", Speaker.B);
        List<Map<String, Object>> all = new ArrayList<>(seen);
        assertThat(all.get(1).get("uri").toString()).startsWith("/v1/text-to-speech/voiceBbb?");
        assertThat(p.voice(Speaker.B).name()).isEqualTo("Bea");
        assertThat(p.voice(Speaker.A).name()).isEqualTo("Matilda");
    }

    @Test
    void retriesRateLimitsThenSucceeds() throws Exception {
        ElevenLabsTtsProvider p = provider(429, 503, 200);
        assertThat(p.synthesize("Retry me.", Speaker.A)).isNotEmpty();
        assertThat(seen).hasSize(3);
    }

    @Test
    void doesNotRetryAuthFailuresAndNeverEchoesTheKeyOrBody() throws Exception {
        ElevenLabsTtsProvider p = provider(401);
        assertThatThrownBy(() -> p.synthesize("Nope.", Speaker.A))
                .isInstanceOf(TtsProvider.TtsException.class)
                .hasMessageContaining("rejected the API key")
                .hasMessageContaining("invalid_api_key")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("echo"));
        assertThat(seen).hasSize(1);
    }

    @Test
    void givesUpAfterThreeAttempts() throws Exception {
        ElevenLabsTtsProvider p = provider(429);
        assertThatThrownBy(() -> p.synthesize("Busy.", Speaker.A)).hasMessageContaining("rate limiting");
        assertThat(seen).hasSize(ElevenLabsTtsProvider.MAX_ATTEMPTS);
    }

    @Test
    void errorMapping() {
        assertThat(ElevenLabsTtsProvider.failure(402, null).getMessage()).contains("credits");
        assertThat(ElevenLabsTtsProvider.failure(404, null).getMessage()).contains("voice");
        assertThat(ElevenLabsTtsProvider.failure(422, "{\"detail\":{\"status\":\"Not A Code!\"}}".getBytes()).getMessage())
                .isEqualTo("ElevenLabs rejected the request");
        assertThat(ElevenLabsTtsProvider.failure(500, "<html>".getBytes()).retryable()).isTrue();
        assertThat(ElevenLabsTtsProvider.failure(400, null).retryable()).isFalse();
    }

    @Test
    void refusesNonMp3OutputAndMissingKey() {
        TtsProperties.ElevenLabs wav = new TtsProperties(null, null, null, null, null,
                new TtsProperties.ElevenLabs(KEY, null, null, "pcm_44100", null, null, null, null, null)).eleven();
        assertThatThrownBy(() -> new ElevenLabsTtsProvider(wav)).hasMessageContaining("MP3");
        TtsProperties.ElevenLabs none = new TtsProperties(null, null, null, null, null, null).eleven();
        assertThatThrownBy(() -> new ElevenLabsTtsProvider(none)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerSelectionAndKeyMasking() {
        TtsProperties.ElevenLabs withKey = new TtsProperties.ElevenLabs(KEY, null, null, null, null, null, null, null, null);
        assertThat(TtsConfig.select(new TtsProperties("auto", null, null, null, null, null))).isSameAs(TtsProvider.NONE);
        assertThat(TtsConfig.select(new TtsProperties(null, null, null, null, null, withKey)).id()).isEqualTo("elevenlabs");
        assertThat(TtsConfig.select(new TtsProperties("none", null, null, null, null, withKey)).available()).isFalse();
        assertThatThrownBy(() -> TtsConfig.select(new TtsProperties("elevenlabs", null, null, null, null, null)))
                .hasMessageContaining("ELEVENLABS_API_KEY");
        assertThatThrownBy(() -> TtsConfig.select(new TtsProperties("polly", null, null, null, null, null))).hasMessageContaining("Unknown");
        assertThat(withKey.toString()).doesNotContain(KEY).contains("<redacted>");
        assertThat(new TtsProperties("auto", null, null, null, null, withKey).toString()).doesNotContain(KEY);
        TtsProperties defaults = new TtsProperties(null, null, null, null, null, null);
        assertThat(defaults.perRequest()).isEqualTo(2_500);
        assertThat(defaults.eleven().model()).isEqualTo("eleven_multilingual_v2");
        assertThat(defaults.eleven().outputFormat()).isEqualTo("mp3_44100_128");
        assertThat(defaults.eleven().voiceB()).isEqualTo(TtsProperties.DEFAULT_VOICE_B);
    }
}
