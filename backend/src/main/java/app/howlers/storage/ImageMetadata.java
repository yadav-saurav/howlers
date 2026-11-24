package app.howlers.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Removes location and identifying metadata from uploaded image originals (D-028) without touching the pixels:
 * segments and chunks are dropped losslessly, nothing is re-encoded.
 *
 * <ul>
 *   <li><b>JPEG.</b> Drops every APP1 (EXIF with GPS, camera make/model/serial, timestamps, the embedded thumbnail; XMP),
 *       APP13 (Photoshop/IPTC), COM comments, APP2 segments other than the ICC profile (MPF/FPXR), JFXX thumbnails,
 *       every other vendor APPn (APP3-APP12, APP15, including C2PA/JUMBF in APP11), and any bytes after the end-of-image
 *       marker (appended secondary images carry their own EXIF). Kept: JFIF, the ICC colour profile (APP2
 *       ICC_PROFILE), Adobe APP14 (needed to decode CMYK/YCCK) and all coding segments, so the entropy-coded data is
 *       byte-identical. If the photo had a non-default EXIF orientation, a new minimal EXIF block holding
 *       <i>only</i> the orientation tag is written, so it still displays upright.</li>
 *   <li><b>PNG.</b> Keeps only a whitelist of rendering chunks (IHDR, PLTE, IDAT, IEND, tRNS, colour chunks such as iCCP,
 *       sRGB, gAMA, cHRM, cICP, plus pHYs, sBIT, bKGD, hIST, sPLT and the APNG chunks). Drops tEXt, zTXt, iTXt (XMP),
 *       eXIf, tIME and any private chunk, plus bytes after IEND. A non-default eXIf orientation is kept the same way as
 *       for JPEG.</li>
 *   <li><b>GIF.</b> Drops comment extensions and application extensions other than the NETSCAPE2.0/ANIMEXTS1.0 loop
 *       control (XMP and ICC live there), and bytes after the trailer. Frames and timing are untouched.</li>
 *   <li><b>WebP.</b> Drops the EXIF and XMP chunks (and any unknown chunk), clears their VP8X flags and fixes the RIFF
 *       size. Keeps ICCP and the image/animation chunks. A non-default orientation is kept as a minimal EXIF chunk.</li>
 * </ul>
 * A file whose structure can't be walked is refused rather than stored with its metadata.
 */
public final class ImageMetadata {
    private ImageMetadata() {}

    /** Thrown when an image's container structure is malformed; callers reject the upload. */
    public static final class MalformedImageException extends RuntimeException {
        MalformedImageException(String message) {
            super(message);
        }
    }

    /** Returns a copy of {@code bytes} without identifying metadata. {@code extension} is png, jpg, gif or webp. */
    public static byte[] strip(byte[] bytes, String extension) {
        try {
            return switch (extension) {
                case "jpg" -> stripJpeg(bytes);
                case "png" -> stripPng(bytes);
                case "gif" -> stripGif(bytes);
                case "webp" -> stripWebp(bytes);
                default -> throw new MalformedImageException("unsupported image type " + extension);
            };
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new MalformedImageException("truncated image");
        }
    }

    // ------------------------------------------------------------------ JPEG

    static byte[] stripJpeg(byte[] b) {
        if (b.length < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) throw new MalformedImageException("not a JPEG");
        int orientation = ImageVariants.jpegOrientation(b);
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        boolean orientationWritten = orientation == 1;
        boolean first = true;
        // Header segments, up to and including the first start-of-scan.
        while (true) {
            if (i + 1 >= b.length) throw new MalformedImageException("JPEG ends before the image data");
            if (u8(b, i) != 0xFF) throw new MalformedImageException("JPEG segment marker expected");
            int marker = u8(b, i + 1);
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD9) throw new MalformedImageException("JPEG has no image data");
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { // stand-alone markers
                i += 2;
                continue;
            }
            int len = u16be(b, i + 2);
            int end = i + 2 + len;
            if (len < 2 || end > b.length) throw new MalformedImageException("JPEG segment length is wrong");
            if (!orientationWritten && !(first && marker == 0xE0)) {
                // EXIF belongs right after SOI, or right after the JFIF APP0 when there is one.
                out.writeBytes(orientationApp1(orientation));
                orientationWritten = true;
            }
            first = false;
            if (keepJpegSegment(marker, b, i + 4, len - 2)) out.write(b, i, end - i);
            i = end;
            if (marker == 0xDA) break;
        }
        // Entropy-coded data, with further segments between scans (progressive JPEGs). Stops at EOI.
        int runStart = i;
        while (i < b.length) {
            if (u8(b, i) != 0xFF || i + 1 >= b.length) {
                i++;
                continue;
            }
            int n = u8(b, i + 1);
            if (n == 0x00 || (n >= 0xD0 && n <= 0xD7)) { // stuffed byte or restart marker: part of the data
                i += 2;
                continue;
            }
            if (n == 0xFF) { // fill byte before a marker
                i++;
                continue;
            }
            out.write(b, runStart, i - runStart);
            if (n == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray(); // anything after EOI is dropped
            }
            int len = u16be(b, i + 2);
            int end = i + 2 + len;
            if (len < 2 || end > b.length) throw new MalformedImageException("JPEG segment length is wrong");
            if (keepJpegSegment(n, b, i + 4, len - 2)) out.write(b, i, end - i);
            i = end;
            runStart = i;
        }
        // Truncated file without EOI: keep the data we have and close it properly.
        out.write(b, runStart, b.length - runStart);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static boolean keepJpegSegment(int marker, byte[] b, int payload, int payloadLen) {
        if (marker == 0xE0) return startsWith(b, payload, payloadLen, "JFIF\0"); // drops JFXX thumbnails
        if (marker == 0xE2) return startsWith(b, payload, payloadLen, "ICC_PROFILE\0");
        if (marker == 0xEE) return startsWith(b, payload, payloadLen, "Adobe");
        if (marker >= 0xE1 && marker <= 0xEF) return false; // EXIF/XMP, IPTC, MPF, vendor and C2PA segments
        return marker != 0xFE; // comments
    }

    private static byte[] orientationApp1(int orientation) {
        byte[] tiff = orientationTiff(orientation);
        int len = 2 + 6 + tiff.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 2);
        o.write(0xFF);
        o.write(0xE1);
        o.write(len >> 8);
        o.write(len & 0xFF);
        o.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes(tiff);
        return o.toByteArray();
    }

    /** A big-endian TIFF structure with one IFD holding only the Orientation tag (0x0112, SHORT). */
    static byte[] orientationTiff(int orientation) {
        return new byte[]{
                'M', 'M', 0, 42, 0, 0, 0, 8, // header, IFD0 at offset 8
                0, 1,                        // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0                   // no next IFD
        };
    }

    /** The Orientation tag from a TIFF structure starting at {@code tiff}, or 1. */
    static int tiffOrientation(byte[] b, int tiff, int limit) {
        try {
            if (tiff + 8 > limit) return 1;
            boolean le = b[tiff] == 'I';
            int ifd = tiff + (int) u32(b, tiff + 4, le);
            if (ifd + 2 > limit || ifd < tiff) return 1;
            int entries = u16(b, ifd, le);
            for (int e = 0; e < entries; e++) {
                int at = ifd + 2 + e * 12;
                if (at + 12 > limit) break;
                if (u16(b, at, le) == 0x0112) {
                    int v = u16(b, at + 8, le);
                    return v >= 1 && v <= 8 ? v : 1;
                }
            }
        } catch (RuntimeException ignored) {
            // malformed: treat as upright
        }
        return 1;
    }

    // ------------------------------------------------------------------ PNG

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    static final Set<String> PNG_KEEP = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT",
            "sRGB", "cICP", "mDCV", "cLLI", "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT");

    static byte[] stripPng(byte[] b) {
        for (int k = 0; k < 8; k++) if (b[k] != PNG_SIGNATURE[k]) throw new MalformedImageException("not a PNG");
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int orientation = 1;
        // First pass: find an eXIf orientation to carry over.
        for (int i = 8; i + 12 <= b.length; ) {
            int len = (int) u32(b, i, false);
            if (len < 0 || i + 12L + len > b.length) break;
            if (type(b, i + 4).equals("eXIf")) orientation = tiffOrientation(b, i + 8, i + 8 + len);
            i += 12 + len;
        }
        boolean orientationWritten = orientation == 1;
        int i = 8;
        while (true) {
            if (i + 12 > b.length) throw new MalformedImageException("PNG ends before IEND");
            long len = u32(b, i, false);
            if (i + 12L + len > b.length) throw new MalformedImageException("PNG chunk length is wrong");
            String type = type(b, i + 4);
            int end = i + 12 + (int) len;
            if (!orientationWritten && (type.equals("IDAT") || type.equals("acTL") || type.equals("fcTL"))) {
                out.writeBytes(pngChunk("eXIf", orientationTiff(orientation)));
                orientationWritten = true;
            }
            if (PNG_KEEP.contains(type)) out.write(b, i, end - i);
            if (type.equals("IEND")) return out.toByteArray(); // bytes after IEND are dropped
            i = end;
        }
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 12);
        writeU32be(o, data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        o.writeBytes(t);
        o.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeU32be(o, crc.getValue());
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ GIF

    static byte[] stripGif(byte[] b) {
        if (b.length < 13 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') throw new MalformedImageException("not a GIF");
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        int packed = u8(b, 10);
        int i = 13 + ((packed & 0x80) != 0 ? 3 * (1 << ((packed & 7) + 1)) : 0);
        if (i > b.length) throw new MalformedImageException("GIF colour table is truncated");
        out.write(b, 0, i);
        while (true) {
            if (i >= b.length) throw new MalformedImageException("GIF ends without a trailer");
            int t = u8(b, i);
            if (t == 0x3B) {
                out.write(0x3B);
                return out.toByteArray(); // bytes after the trailer are dropped
            }
            if (t == 0x21) {
                int label = u8(b, i + 1);
                int end = skipSubBlocks(b, i + 2);
                boolean keep = switch (label) {
                    case 0xF9, 0x01 -> true; // graphic control (frame timing, transparency), plain text (rendered)
                    case 0xFF -> u8(b, i + 2) == 11 && (startsWith(b, i + 3, 11, "NETSCAPE2.0") || startsWith(b, i + 3, 11, "ANIMEXTS1.0"));
                    default -> false;        // comments (0xFE), XMP/ICC and other application data
                };
                if (keep) out.write(b, i, end - i);
                i = end;
            } else if (t == 0x2C) {
                if (i + 10 > b.length) throw new MalformedImageException("GIF image descriptor is truncated");
                int p = u8(b, i + 9);
                int j = i + 10 + ((p & 0x80) != 0 ? 3 * (1 << ((p & 7) + 1)) : 0);
                int end = skipSubBlocks(b, j + 1); // +1: LZW minimum code size
                out.write(b, i, end - i);
                i = end;
            } else {
                throw new MalformedImageException("GIF block type is unknown");
            }
        }
    }

    private static int skipSubBlocks(byte[] b, int j) {
        while (true) {
            if (j >= b.length) throw new MalformedImageException("GIF data is truncated");
            int size = u8(b, j);
            j += 1 + size;
            if (size == 0) return j;
        }
    }

    // ------------------------------------------------------------------ WebP

    static final Set<String> WEBP_KEEP = Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP");

    static byte[] stripWebp(byte[] b) {
        if (b.length < 20 || !startsWith(b, 0, 4, "RIFF") || !startsWith(b, 8, 4, "WEBP")) {
            throw new MalformedImageException("not a WebP");
        }
        long riff = u32(b, 4, true);
        int limit = (int) Math.min(b.length, 8 + riff); // bytes after the RIFF payload are dropped
        int orientation = 1;
        for (int i = 12; i + 8 <= limit; ) {
            long len = u32(b, i + 4, true);
            if (i + 8 + len > limit) break;
            if (type(b, i).equals("EXIF")) {
                int tiff = i + 8;
                if (startsWith(b, tiff, (int) len, "Exif\0\0")) tiff += 6;
                orientation = tiffOrientation(b, tiff, i + 8 + (int) len);
            }
            i += 8 + (int) len + (int) (len & 1);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        boolean hasVp8x = false;
        int i = 12;
        while (i < limit) {
            if (i + 8 > limit) throw new MalformedImageException("WebP chunk header is truncated");
            String type = type(b, i);
            long len = u32(b, i + 4, true);
            int end = i + 8 + (int) len + (int) (len & 1);
            if (i + 8 + len > limit) throw new MalformedImageException("WebP chunk length is wrong");
            end = Math.min(end, limit);
            if (WEBP_KEEP.contains(type)) {
                byte[] chunk = java.util.Arrays.copyOfRange(b, i, end);
                if (type.equals("VP8X") && len >= 10) {
                    hasVp8x = true;
                    chunk[8] &= (byte) ~0x0C; // clear the EXIF (0x08) and XMP (0x04) flags
                    if (orientation != 1) chunk[8] |= 0x08;
                }
                body.writeBytes(chunk);
                if (chunk.length % 2 == 1) body.write(0);
            }
            i = end;
        }
        if (orientation != 1 && hasVp8x) {
            byte[] tiff = orientationTiff(orientation);
            body.writeBytes("EXIF".getBytes(StandardCharsets.US_ASCII));
            writeU32le(body, tiff.length);
            body.writeBytes(tiff); // even length, no padding needed
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 12);
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeU32le(out, body.size() + 4L);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16be(byte[] b, int at) {
        return (u8(b, at) << 8) | u8(b, at + 1);
    }

    private static int u16(byte[] b, int at, boolean le) {
        return le ? u8(b, at) | (u8(b, at + 1) << 8) : u16be(b, at);
    }

    private static long u32(byte[] b, int at, boolean le) {
        long v = le ? u8(b, at) | ((long) u8(b, at + 1) << 8) | ((long) u8(b, at + 2) << 16) | ((long) u8(b, at + 3) << 24)
                : ((long) u8(b, at) << 24) | ((long) u8(b, at + 1) << 16) | ((long) u8(b, at + 2) << 8) | u8(b, at + 3);
        return v & 0xFFFFFFFFL;
    }

    private static void writeU32be(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) v & 0xFF);
    }

    private static void writeU32le(ByteArrayOutputStream o, long v) {
        o.write((int) v & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 24) & 0xFF);
    }

    private static String type(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.ISO_8859_1);
    }

    private static boolean startsWith(byte[] b, int at, int available, String prefix) {
        if (prefix.length() > available || at + prefix.length() > b.length) return false;
        for (int k = 0; k < prefix.length(); k++) if (b[at + k] != (byte) prefix.charAt(k)) return false;
        return true;
    }
}
