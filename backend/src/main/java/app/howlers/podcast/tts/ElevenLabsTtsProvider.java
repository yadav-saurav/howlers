package app.howlers.podcast.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ElevenLabs text-to-speech over its REST API with the JDK {@link HttpClient} (no SDK).
 *
 * <p>{@code POST {baseUrl}/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128} with the {@code xi-api-key}
 * header and {@code {"text", "model_id"}} as JSON returns the MP3 bytes. 429 and 5xx answers are retried with
 * backoff; other failures are reported with the HTTP status and ElevenLabs' short status code only (never the key, never
 * the raw body). Only premade voices configured by the operator are used.
 */
public class ElevenLabsTtsProvider implements TtsProvider {
    private static final Logger log = LoggerFactory.getLogger(ElevenLabsTtsProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_ATTEMPTS = 3;

    private final TtsProperties.ElevenLabs cfg;
    private final HttpClient http;
    private final long backoffMs;

    public ElevenLabsTtsProvider(TtsProperties.ElevenLabs cfg) {
        this(cfg, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(), 1_500);
    }

    ElevenLabsTtsProvider(TtsProperties.ElevenLabs cfg, HttpClient http, long backoffMs) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) throw new IllegalArgumentException("ElevenLabs needs an API key");
        if (!cfg.outputFormat().startsWith("mp3_")) {
            throw new IllegalArgumentException("ELEVENLABS_OUTPUT_FORMAT must be an MP3 format (e.g. mp3_44100_128): episodes are built by joining MP3 frames");
        }
        this.cfg = cfg;
        this.http = http;
        this.backoffMs = backoffMs;
    }

    @Override public String id() { return "elevenlabs"; }
    @Override public String label() { return "ElevenLabs"; }
    @Override public String model() { return cfg.model(); }
    @Override public boolean available() { return true; }

    @Override
    public Voice voice(Speaker s) {
        return s == Speaker.A ? new Voice(s, cfg.voiceA(), cfg.voiceAName()) : new Voice(s, cfg.voiceB(), cfg.voiceBName());
    }

    HttpRequest request(String text, Speaker speaker) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("model_id", cfg.model());
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String voiceId = URLEncoder.encode(voice(speaker).voiceId(), StandardCharsets.UTF_8);
        URI uri = URI.create(cfg.baseUrl() + "/v1/text-to-speech/" + voiceId + "?output_format="
                + URLEncoder.encode(cfg.outputFormat(), StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri)
                .timeout(cfg.requestTimeout())
                .header("xi-api-key", cfg.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public byte[] synthesize(String text, Speaker speaker) throws TtsException, InterruptedException {
        HttpRequest req = request(text, speaker);
        TtsException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = res.statusCode();
                if (status == 200) {
                    byte[] audio = res.body();
                    if (audio == null || audio.length == 0) throw new TtsException("ElevenLabs returned no audio", true);
                    return audio;
                }
                last = failure(status, res.body());
            } catch (IOException e) {
                // Never include the exception message: some include the request URI or headers.
                last = new TtsException("Couldn't reach ElevenLabs (" + e.getClass().getSimpleName() + ")", true);
            }
            log.warn("ElevenLabs request failed (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, last.getMessage());
            if (!last.retryable() || attempt == MAX_ATTEMPTS) break;
            Thread.sleep(backoffMs * attempt);
        }
        throw last;
    }

    /** Maps an error answer to a moderator-safe message: HTTP status plus ElevenLabs' short status code. */
    static TtsException failure(int status, byte[] body) {
        String code = errorCode(body);
        String suffix = code == null ? "" : " (" + code + ")";
        return switch (status) {
            case 401, 403 -> new TtsException("ElevenLabs rejected the API key or its permissions" + suffix, false);
            case 402 -> new TtsException("The ElevenLabs account is out of credits or needs a paid plan" + suffix, false);
            case 404 -> new TtsException("ElevenLabs couldn't find the configured voice" + suffix, false);
            case 400, 422 -> new TtsException("ElevenLabs rejected the request" + suffix, false);
            case 429 -> new TtsException("ElevenLabs is rate limiting this account" + suffix, true);
            default -> new TtsException("ElevenLabs answered HTTP " + status + suffix, status >= 500);
        };
    }

    /** {@code {"detail": {"status": "quota_exceeded", "message": ...}}}: keep only a short, code-like status. */
    static String errorCode(byte[] body) {
        if (body == null || body.length == 0 || body.length > 64_000) return null;
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode s = n.path("detail").path("status");
            if (s.isMissingNode()) s = n.path("detail").path("code");
            String v = s.isTextual() ? s.asText() : null;
            return v != null && v.matches("[a-z0-9_]{1,60}") ? v : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ElevenLabsTtsProvider[model=" + cfg.model() + "]";
    }
}
