package app.howlers.podcast.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the podcast TTS provider from {@code howlers.podcasts.tts.provider} (D-036). */
@Configuration(proxyBeanMethods = false)
public class TtsConfig {
    private static final Logger log = LoggerFactory.getLogger(TtsConfig.class);

    @Bean
    TtsProvider ttsProvider(TtsProperties props) {
        return select(props);
    }

    static TtsProvider select(TtsProperties props) {
        TtsProperties.ElevenLabs eleven = props.eleven();
        boolean hasKey = eleven.apiKey() != null;
        TtsProvider p = switch (props.providerId()) {
            case "none", "off", "disabled" -> TtsProvider.NONE;
            case "auto" -> hasKey ? new ElevenLabsTtsProvider(eleven) : TtsProvider.NONE;
            case "elevenlabs" -> {
                if (!hasKey) throw new IllegalStateException("howlers.podcasts.tts.provider=elevenlabs needs ELEVENLABS_API_KEY");
                yield new ElevenLabsTtsProvider(eleven);
            }
            default -> throw new IllegalStateException("Unknown howlers.podcasts.tts.provider '" + props.providerId()
                    + "': use auto, elevenlabs or none");
        };
        if (p.available()) log.info("Podcast audio generation: {} ({}), voices A={} B={}", p.label(), p.model(),
                p.voice(TtsProvider.Speaker.A).voiceId(), p.voice(TtsProvider.Speaker.B).voiceId());
        else log.info("Podcast audio generation unavailable (no TTS provider configured)");
        return p;
    }
}
