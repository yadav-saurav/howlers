package app.howlers.podcast.tts;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough MPEG audio (Layer III) parsing to join synthesised parts into one file and to time them.
 *
 * <ul>
 *   <li>{@link #parse} walks frame headers, skipping ID3v2 tags, ID3v1/APE trailers and anything that isn't a frame
 *       (it resyncs only on two consecutive valid headers). Xing/Info/VBRI header frames are dropped: they describe one
 *       part's length and would make players misreport the joined file's duration.</li>
 *   <li>Duration is the sum of samples per frame over the sample rate, i.e. measured from the audio, not estimated
 *       from text.</li>
 *   <li>{@link #silence} builds frames whose side info and main data are all zero, which decode as digital silence;
 *       they separate speaker turns and serve as a real, playable test fixture.</li>
 * </ul>
 */
public final class Mp3 {
    private Mp3() {}

    private static final int[] BITRATE_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BITRATE_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[][] SAMPLE_RATES = {
            {11025, 12000, 8000},  // MPEG 2.5 (version bits 00)
            {0, 0, 0},             // reserved
            {22050, 24000, 16000}, // MPEG 2
            {44100, 48000, 32000}, // MPEG 1
    };

    /** A decoded frame header. {@code version}: 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5. */
    public record Header(int version, int bitrateKbps, int sampleRate, boolean padding, boolean crc, int channelMode, int length,
                         int samples) {
        boolean mono() {
            return channelMode == 3;
        }

        int sideInfo() {
            return version == 3 ? (mono() ? 17 : 32) : (mono() ? 9 : 17);
        }
    }

    /** One part's frames (offset/length into {@code data}) with the Xing/Info frame and tags removed. */
    public record Parsed(byte[] data, List<int[]> frames, Header first, long samples) {
        public double seconds() {
            return first == null ? 0 : (double) samples / first.sampleRate();
        }

        public int frameCount() {
            return frames.size();
        }

        public long audioBytes() {
            long n = 0;
            for (int[] f : frames) n += f[1];
            return n;
        }
    }

    public static Header header(byte[] b, int i) {
        if (i + 4 > b.length) return null;
        int b0 = b[i] & 0xFF, b1 = b[i + 1] & 0xFF, b2 = b[i + 2] & 0xFF, b3 = b[i + 3] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int version = (b1 >> 3) & 3;
        int layer = (b1 >> 1) & 3;
        if (version == 1 || layer != 1) return null; // reserved version, or not Layer III
        int bIdx = (b2 >> 4) & 0xF, srIdx = (b2 >> 2) & 3;
        if (bIdx == 0 || bIdx == 15 || srIdx == 3) return null; // free-format bitrate is not supported
        int kbps = version == 3 ? BITRATE_V1_L3[bIdx] : BITRATE_V2_L3[bIdx];
        int sr = SAMPLE_RATES[version][srIdx];
        boolean pad = ((b2 >> 1) & 1) == 1;
        int samples = version == 3 ? 1152 : 576;
        int length = (samples / 8) * kbps * 1000 / sr + (pad ? 1 : 0);
        return new Header(version, kbps, sr, pad, (b1 & 1) == 0, (b3 >> 6) & 3, length, samples);
    }

    public static Parsed parse(byte[] data) {
        List<int[]> frames = new ArrayList<>();
        Header first = null;
        long samples = 0;
        int i = 0;
        boolean synced = false; // the previous frame ended exactly here, so one valid header is enough
        while (i < data.length) {
            int skip = id3v2Length(data, i);
            if (skip > 0) {
                i += skip;
                synced = false;
                continue;
            }
            Header h = header(data, i);
            int next = h == null ? -1 : i + h.length();
            boolean valid = h != null && next <= data.length
                    && (synced || next == data.length || header(data, next) != null || trailerAt(data, next));
            if (!valid) {
                i++;
                synced = false;
                continue;
            }
            synced = true;
            if (first != null && h.sampleRate() != first.sampleRate()) {
                throw new IllegalArgumentException("MP3 changes sample rate mid-stream");
            }
            if (!isInfoFrame(data, i, h)) {
                if (first == null) first = h;
                frames.add(new int[] {i, h.length()});
                samples += h.samples();
            }
            i += h.length();
        }
        return new Parsed(data, frames, first, samples);
    }

    /** Duration in seconds measured from the frames. */
    public static double seconds(byte[] data) {
        return parse(data).seconds();
    }

    /** Builds {@code count} frames of digital silence matching {@code like}'s format (no CRC, no padding). */
    public static byte[] silence(Header like, int count) {
        int kbpsIdx = indexOf(like.version() == 3 ? BITRATE_V1_L3 : BITRATE_V2_L3, like.bitrateKbps());
        int srIdx = indexOf(SAMPLE_RATES[like.version()], like.sampleRate());
        int length = (like.samples() / 8) * like.bitrateKbps() * 1000 / like.sampleRate();
        byte[] out = new byte[length * count];
        for (int f = 0; f < count; f++) {
            int o = f * length;
            out[o] = (byte) 0xFF;
            out[o + 1] = (byte) (0xE0 | (like.version() << 3) | (1 << 1) | 1); // Layer III, no CRC
            out[o + 2] = (byte) ((kbpsIdx << 4) | (srIdx << 2));
            out[o + 3] = (byte) (like.channelMode() << 6);
        }
        return out;
    }

    /** Silence frames of the common ElevenLabs format (MPEG1 Layer III, 44.1 kHz, 128 kbps, mono). Test fixtures use it. */
    public static byte[] silence44k(int count) {
        return silence(new Header(3, 128, 44100, false, false, 3, 417, 1152), count);
    }

    /** Joins parts into one stream, dropping tags and Xing frames, and inserting silent gaps on request. */
    public static final class Writer {
        private final OutputStream out;
        private Header format;
        private long samples;
        private long bytes;

        public Writer(OutputStream out) {
            this.out = out;
        }

        /** Appends one part and returns its duration in seconds. */
        public double append(byte[] part) throws IOException {
            Parsed p = parse(part);
            if (p.first() == null) throw new IOException("The audio part contains no MP3 frames");
            if (format == null) format = p.first();
            else if (p.first().sampleRate() != format.sampleRate() || p.first().version() != format.version()) {
                throw new IOException("Audio parts use different MP3 formats (" + p.first().sampleRate() + " vs " + format.sampleRate() + " Hz)");
            }
            for (int[] f : p.frames()) out.write(p.data(), f[0], f[1]);
            samples += p.samples();
            bytes += p.audioBytes();
            return p.seconds();
        }

        /** Appends at least {@code ms} of silence (whole frames) and returns the seconds added. Needs a prior part. */
        public double silence(int ms) throws IOException {
            if (format == null || ms <= 0) return 0;
            int frames = (int) Math.ceil(ms / 1000.0 * format.sampleRate() / format.samples());
            byte[] s = Mp3.silence(format, frames);
            out.write(s);
            samples += (long) frames * format.samples();
            bytes += s.length;
            return (double) frames * format.samples() / format.sampleRate();
        }

        public double seconds() {
            return format == null ? 0 : (double) samples / format.sampleRate();
        }

        public long bytes() {
            return bytes;
        }
    }

    // ------------------------------------------------------------------ tags

    static int id3v2Length(byte[] b, int i) {
        if (i + 10 > b.length || !startsWith(b, i, "ID3")) return 0;
        int size = ((b[i + 6] & 0x7F) << 21) | ((b[i + 7] & 0x7F) << 14) | ((b[i + 8] & 0x7F) << 7) | (b[i + 9] & 0x7F);
        boolean footer = (b[i + 5] & 0x10) != 0;
        return Math.min(b.length - i, 10 + size + (footer ? 10 : 0));
    }

    private static boolean trailerAt(byte[] b, int i) {
        return startsWith(b, i, "TAG") || startsWith(b, i, "APETAGEX") || startsWith(b, i, "ID3");
    }

    static boolean isInfoFrame(byte[] b, int i, Header h) {
        int off = i + 4 + (h.crc() ? 2 : 0) + h.sideInfo();
        return startsWith(b, off, "Xing") || startsWith(b, off, "Info") || startsWith(b, i + 36, "VBRI");
    }

    private static boolean startsWith(byte[] b, int i, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        if (i < 0 || i + p.length > b.length) return false;
        for (int k = 0; k < p.length; k++) if (b[i + k] != p[k]) return false;
        return true;
    }

    private static int indexOf(int[] a, int v) {
        for (int k = 0; k < a.length; k++) if (a[k] == v) return k;
        throw new IllegalArgumentException("Unsupported MP3 parameter " + v);
    }
}
