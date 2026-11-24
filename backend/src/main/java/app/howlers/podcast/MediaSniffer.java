package app.howlers.podcast;

import app.howlers.common.error.ApiException;
import java.nio.charset.StandardCharsets;

/**
 * Identifies audio/video containers from their leading bytes, never from the filename or client Content-Type.
 * Only formats browsers can play natively are accepted.
 */
public final class MediaSniffer {
    private MediaSniffer() {}

    public record Kind(String mediaKind, String extension, String contentType) {}

    public static Kind sniff(byte[] h) {
        if (h.length >= 12 && ascii(h, 4, 4).equals("ftyp")) {
            String brand = ascii(h, 8, 4);
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) return new Kind("AUDIO", "m4a", "audio/mp4");
            return new Kind("VIDEO", "mp4", "video/mp4");
        }
        if (h.length >= 3 && ascii(h, 0, 3).equals("ID3")) return new Kind("AUDIO", "mp3", "audio/mpeg");
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return new Kind("AUDIO", "mp3", "audio/mpeg");
        if (h.length >= 4 && ascii(h, 0, 4).equals("OggS")) return new Kind("AUDIO", "ogg", "audio/ogg");
        if (h.length >= 12 && ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WAVE")) return new Kind("AUDIO", "wav", "audio/wav");
        if (h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return new Kind("VIDEO", "webm", "video/webm");
        }
        throw ApiException.field("file", "Unsupported media. Use MP3, M4A, OGG or WAV audio, or MP4 or WebM video");
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }
}
