package app.howlers.podcast.tts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Podcast text-to-speech configuration ({@code howlers.podcasts.tts.*}, see D-036).
 *
 * @param provider               {@code auto} (ElevenLabs when {@code ELEVENLABS_API_KEY} is set, otherwise none),
 *                               {@code elevenlabs} (refuses to start without a key) or {@code none}
 * @param maxCharsPerRequest     the longest text sent in one synthesis request; long turns are split at sentence boundaries
 * @param maxCharsPerEpisode     the longest script (in spoken characters) one generation may synthesise
 * @param dailyCharacterBudget   characters each community may synthesise per UTC day (cost control)
 * @param turnPauseMs            silence inserted between speaker turns
 * @param elevenlabs             ElevenLabs settings; the key is read from the server environment only
 */
@ConfigurationProperties(prefix = "howlers.podcasts.tts")
public record TtsProperties(String provider, Integer maxCharsPerRequest, Integer maxCharsPerEpisode, Integer dailyCharacterBudget,
                            Integer turnPauseMs, ElevenLabs elevenlabs) {

    /**
     * @param apiKey        never logged or returned by the API
     * @param baseUrl       {@code https://api.elevenlabs.io} unless you route through a proxy
     * @param model         model id sent as {@code model_id}
     * @param outputFormat  sent as the {@code output_format} query parameter; must be an MP3 format (frames are concatenated)
     * @param voiceA        premade voice id for HOST A
     * @param voiceB        premade voice id for HOST B
     */
    public record ElevenLabs(String apiKey, String baseUrl, String model, String outputFormat, String voiceA, String voiceAName,
                             String voiceB, String voiceBName, Duration requestTimeout) {
        @Override
        public String toString() {
            return "ElevenLabs[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>") + ", model=" + model
                    + ", outputFormat=" + outputFormat + ", voiceA=" + voiceA + ", voiceB=" + voiceB + "]";
        }
    }

    /** Matilda (premade, professional alto), used for HOST A unless {@code ELEVENLABS_VOICE_A} is set. */
    public static final String DEFAULT_VOICE_A = "XrExE9yKIg1WjnnlVkGX";
    /** George (premade, British, warm narrator), used for HOST B unless {@code ELEVENLABS_VOICE_B} is set. */
    public static final String DEFAULT_VOICE_B = "JBFqnCBsd6RMkjVDRZzb";

    public String providerId() {
        return provider == null || provider.isBlank() ? "auto" : provider.strip().toLowerCase();
    }

    public int perRequest() {
        return maxCharsPerRequest == null || maxCharsPerRequest < 200 ? 2_500 : maxCharsPerRequest;
    }

    public int perEpisode() {
        return maxCharsPerEpisode == null || maxCharsPerEpisode < 1 ? 60_000 : maxCharsPerEpisode;
    }

    public int dailyBudget() {
        return dailyCharacterBudget == null || dailyCharacterBudget < 0 ? 100_000 : dailyCharacterBudget;
    }

    public int pauseMs() {
        return turnPauseMs == null || turnPauseMs < 0 ? 350 : Math.min(turnPauseMs, 3_000);
    }

    public ElevenLabs eleven() {
        ElevenLabs e = elevenlabs == null ? new ElevenLabs(null, null, null, null, null, null, null, null, null) : elevenlabs;
        return new ElevenLabs(blank(e.apiKey()) ? null : e.apiKey().strip(),
                blank(e.baseUrl()) ? "https://api.elevenlabs.io" : e.baseUrl().strip().replaceAll("/+$", ""),
                blank(e.model()) ? "eleven_multilingual_v2" : e.model().strip(),
                blank(e.outputFormat()) ? "mp3_44100_128" : e.outputFormat().strip(),
                blank(e.voiceA()) ? DEFAULT_VOICE_A : e.voiceA().strip(),
                blank(e.voiceAName()) ? (blank(e.voiceA()) ? "Matilda" : "Custom voice A") : e.voiceAName().strip(),
                blank(e.voiceB()) ? DEFAULT_VOICE_B : e.voiceB().strip(),
                blank(e.voiceBName()) ? (blank(e.voiceB()) ? "George" : "Custom voice B") : e.voiceBName().strip(),
                e.requestTimeout() == null ? Duration.ofMinutes(2) : e.requestTimeout());
    }

    /** Masks the key in any accidental toString (records print every component). */
    @Override
    public String toString() {
        return "TtsProperties[provider=" + providerId() + ", perRequest=" + perRequest() + ", perEpisode=" + perEpisode()
                + ", dailyBudget=" + dailyBudget() + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
