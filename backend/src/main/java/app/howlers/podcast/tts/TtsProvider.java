package app.howlers.podcast.tts;

/**
 * Text-to-speech for podcast scripts (D-036). Implementations return MP3 audio, since the generator concatenates MP3
 * frames. Only stock voices chosen by the operator are used: there is no voice cloning or voice upload.
 */
public interface TtsProvider {

    /** Two-host scripts: {@code A} is HOST A, {@code B} is HOST B. */
    enum Speaker { A, B }

    record Voice(Speaker speaker, String voiceId, String name) {}

    /** Stable id stored with the episode, e.g. {@code elevenlabs}. */
    String id();

    /** Human-readable provider name for the label ("ElevenLabs"). */
    String label();

    /** Model id stored with the episode. */
    String model();

    boolean available();

    Voice voice(Speaker speaker);

    /**
     * Synthesise one chunk (at most the configured per-request character limit) and return MP3 bytes.
     * Must respond to thread interruption, which is how a moderator's cancel stops an in-flight request.
     */
    byte[] synthesize(String text, Speaker speaker) throws TtsException, InterruptedException;

    /** A failure safe to show to moderators: never contains the API key or raw provider bodies. */
    class TtsException extends Exception {
        private final boolean retryable;

        public TtsException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** The provider used when no key is configured: audio generation is unavailable. */
    TtsProvider NONE = new TtsProvider() {
        @Override public String id() { return "none"; }
        @Override public String label() { return "None"; }
        @Override public String model() { return ""; }
        @Override public boolean available() { return false; }
        @Override public Voice voice(Speaker s) { return new Voice(s, "", ""); }
        @Override public byte[] synthesize(String text, Speaker speaker) throws TtsException {
            throw new TtsException("Audio generation isn't configured on this server", false);
        }
    };
}
