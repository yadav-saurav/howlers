package app.howlers.podcast.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** MP3 frame parsing, duration and concatenation on real (silent) MPEG1 Layer III frames built by {@link Mp3#silence}. */
class Mp3Test {
    /** 1152 samples per frame at 44.1 kHz. */
    private static final double FRAME_S = 1152.0 / 44100;

    /** An ID3v2.4 tag with a {@code size}-byte body (syncsafe length). */
    static byte[] id3v2(int size) {
        byte[] b = new byte[10 + size];
        b[0] = 'I'; b[1] = 'D'; b[2] = '3'; b[3] = 4;
        b[6] = (byte) ((size >> 21) & 0x7F); b[7] = (byte) ((size >> 14) & 0x7F); b[8] = (byte) ((size >> 7) & 0x7F); b[9] = (byte) (size & 0x7F);
        for (int i = 10; i < b.length; i++) b[i] = (byte) 0xFF; // sync-looking bytes inside the tag must not be read as frames
        return b;
    }

    /** A silent frame carrying a LAME "Info" header, the way encoders write CBR files. */
    static byte[] infoFrame() {
        byte[] f = Mp3.silence44k(1);
        System.arraycopy("Info".getBytes(StandardCharsets.ISO_8859_1), 0, f, 4 + 17, 4); // mono: 17 bytes of side info
        return f;
    }

    static byte[] id3v1() {
        byte[] b = new byte[128];
        b[0] = 'T'; b[1] = 'A'; b[2] = 'G';
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    /** What a TTS API typically returns: ID3 tag, Xing/Info frame, audio frames, sometimes an ID3v1 trailer. */
    static byte[] providerPart(int frames) {
        return concat(id3v2(40), infoFrame(), Mp3.silence44k(frames), id3v1());
    }

    @Test
    void parsesAKnownHeader() {
        // FF FB 90 64: MPEG1 Layer III, no CRC, 128 kbps, 44.1 kHz, no padding, joint stereo.
        Mp3.Header h = Mp3.header(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x64}, 0);
        assertThat(h).isNotNull();
        assertThat(h.version()).isEqualTo(3);
        assertThat(h.bitrateKbps()).isEqualTo(128);
        assertThat(h.sampleRate()).isEqualTo(44100);
        assertThat(h.length()).isEqualTo(417);
        assertThat(h.samples()).isEqualTo(1152);
        assertThat(h.crc()).isFalse();
        // Padding adds one byte; MPEG2 at 22.05 kHz uses 576-sample frames.
        assertThat(Mp3.header(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x92, (byte) 0x64}, 0).length()).isEqualTo(418);
        Mp3.Header v2 = Mp3.header(new byte[] {(byte) 0xFF, (byte) 0xF3, (byte) 0x80, (byte) 0xC4}, 0);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.sampleRate()).isEqualTo(22050);
        assertThat(v2.samples()).isEqualTo(576);
        // Not Layer III, bad bitrate, or no sync: rejected.
        assertThat(Mp3.header(new byte[] {(byte) 0xFF, (byte) 0xFD, (byte) 0x90, 0}, 0)).isNull();
        assertThat(Mp3.header(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0xF0, 0}, 0)).isNull();
        assertThat(Mp3.header(new byte[] {(byte) 0x49, (byte) 0x44, (byte) 0x33, 0}, 0)).isNull();
    }

    @Test
    void durationComesFromTheFrames() {
        byte[] oneSecondish = Mp3.silence44k(38);
        Mp3.Parsed p = Mp3.parse(oneSecondish);
        assertThat(p.frameCount()).isEqualTo(38);
        assertThat(p.seconds()).isCloseTo(38 * FRAME_S, within(1e-9));
        assertThat(Mp3.seconds(oneSecondish)).isCloseTo(0.9927, within(0.001));
    }

    @Test
    void tagsAndInfoFramesAreSkipped() {
        Mp3.Parsed p = Mp3.parse(providerPart(10));
        assertThat(p.frameCount()).isEqualTo(10); // the Info frame isn't audio
        assertThat(p.seconds()).isCloseTo(10 * FRAME_S, within(1e-9));
        assertThat(p.audioBytes()).isEqualTo(10 * 417L);
        assertThat(Mp3.isInfoFrame(infoFrame(), 0, Mp3.header(infoFrame(), 0))).isTrue();
    }

    @Test
    void garbageBetweenFramesIsSkippedByResync() {
        byte[] noisy = concat(Mp3.silence44k(3), new byte[] {1, 2, 3, (byte) 0xFF, (byte) 0xFB, 9}, Mp3.silence44k(2));
        assertThat(Mp3.parse(noisy).frameCount()).isEqualTo(5);
        assertThat(Mp3.parse(new byte[] {1, 2, 3}).first()).isNull();
    }

    @Test
    void writerJoinsPartsStripsTagsAndAddsSilence() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Mp3.Writer w = new Mp3.Writer(out);
        assertThat(w.silence(500)).isZero(); // no format known yet
        double a = w.append(providerPart(20));
        double gap = w.silence(350);
        double b = w.append(providerPart(30));
        assertThat(a).isCloseTo(20 * FRAME_S, within(1e-9));
        assertThat(gap).isGreaterThanOrEqualTo(0.35).isLessThan(0.35 + FRAME_S);
        assertThat(b).isCloseTo(30 * FRAME_S, within(1e-9));

        byte[] joined = out.toByteArray();
        assertThat(joined.length).isEqualTo(w.bytes());
        // The result is one clean stream: starts with a frame, no tags or Info frames anywhere, durations add up.
        assertThat(joined[0] & 0xFF).isEqualTo(0xFF);
        String text = new String(joined, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("ID3").doesNotContain("TAG").doesNotContain("Info");
        Mp3.Parsed p = Mp3.parse(joined);
        assertThat(p.seconds()).isCloseTo(w.seconds(), within(1e-9));
        assertThat(p.seconds()).isCloseTo(a + gap + b, within(1e-9));
        assertThat(p.frameCount()).isEqualTo(20 + 14 + 30);
    }

    @Test
    void writerRejectsMixedSampleRatesAndEmptyParts() throws IOException {
        Mp3.Writer w = new Mp3.Writer(new ByteArrayOutputStream());
        w.append(Mp3.silence44k(2));
        byte[] at48k = Mp3.silence(new Mp3.Header(3, 128, 48000, false, false, 3, 384, 1152), 2);
        assertThat(Mp3.parse(at48k).first().sampleRate()).isEqualTo(48000);
        assertThatThrownBy(() -> w.append(at48k)).isInstanceOf(IOException.class).hasMessageContaining("different MP3 formats");
        assertThatThrownBy(() -> w.append("not audio".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
    }
}
