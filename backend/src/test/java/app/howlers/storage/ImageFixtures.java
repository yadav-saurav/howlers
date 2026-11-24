package app.howlers.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Real image bytes with privacy-sensitive metadata, built to the EXIF 2.3 / PNG / GIF89a / WebP container specs:
 * a camera make and model, an orientation tag and a GPS IFD (latitude/longitude as rationals), plus XMP, IPTC,
 * comments and trailing data. Used by the metadata stripping tests.
 */
public final class ImageFixtures {
    private ImageFixtures() {}

    public static final String MAKE = "HowlerTestCam";
    public static final String MODEL = "SecretPhone 9";

    /** EXIF TIFF structure (big-endian): IFD0 with Make, Model, Orientation and a GPS IFD pointer; GPS IFD with lat/lon. */
    public static byte[] exifTiff(int orientation) {
        ByteBuffer b = ByteBuffer.allocate(192).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{'M', 'M', 0, 42}).putInt(8);
        // IFD0 at 8: 4 entries, ends at 62
        b.putShort((short) 4);
        entry(b, 0x010F, 2, 14, 62);            // Make -> 62
        entry(b, 0x0110, 2, 14, 76);            // Model -> 76
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        entry(b, 0x8825, 4, 1, 90);             // GPSInfo IFD -> 90
        b.putInt(0);
        b.put((MAKE + "\0").getBytes(StandardCharsets.US_ASCII));   // 62..76
        b.put((MODEL + "\0").getBytes(StandardCharsets.US_ASCII));  // 76..90
        // GPS IFD at 90: 4 entries, ends at 144
        b.putShort((short) 4);
        b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[]{'N', 0, 0, 0});
        entry(b, 0x0002, 5, 3, 144);            // GPSLatitude -> 144
        b.putShort((short) 0x0003).putShort((short) 2).putInt(2).put(new byte[]{'E', 0, 0, 0});
        entry(b, 0x0004, 5, 3, 168);            // GPSLongitude -> 168
        b.putInt(0);
        rational(b, 48, 1); rational(b, 51, 1); rational(b, 2945, 100);  // 48° 51' 29.45"
        rational(b, 2, 1); rational(b, 17, 1); rational(b, 4002, 100);   // 2° 17' 40.02"
        return b.array();
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int offset) {
        b.putShort((short) tag).putShort((short) type).putInt(count).putInt(offset);
    }

    private static void rational(ByteBuffer b, int num, int den) {
        b.putInt(num).putInt(den);
    }

    public static BufferedImage picture(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 120, 200));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.ORANGE);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return img;
    }

    static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) throw new IllegalStateException("no writer for " + format);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] segment(int marker, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF);
        o.write(marker);
        int len = payload.length + 2;
        o.write(len >> 8);
        o.write(len & 0xFF);
        o.writeBytes(payload);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A JPEG as a phone would write it: EXIF with GPS first, then XMP, IPTC, a comment, and data after EOI. */
    public static byte[] jpegWithGps(int w, int h, int orientation) {
        byte[] plain = encode(picture(w, h), "jpg");
        // ImageIO writes SOI + JFIF APP0 first; insert the metadata right after the APP0.
        int app0End = 2 + 2 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        byte[] exif = segment(0xE1, concat(ascii("Exif\0\0"), exifTiff(orientation)));
        byte[] xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta><exif:GPSLatitude>48,51.49N</exif:GPSLatitude>"
                + "<tiff:Make>" + MAKE + "</tiff:Make></x:xmpmeta>"));
        byte[] iptc = segment(0xED, ascii("Photoshop 3.0\08BIM\u0004\u0004\0\0\0\0\0\u0010\u001c\u0002Z\0\u0008Paris-FR"));
        byte[] com = segment(0xFE, ascii("taken at home by " + MODEL));
        byte[] trailer = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, segment(0xE1, concat(ascii("Exif\0\0"), exifTiff(1))),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        return concat(java.util.Arrays.copyOfRange(plain, 0, app0End), exif, xmp, iptc, com,
                java.util.Arrays.copyOfRange(plain, app0End, plain.length), trailer);
    }

    public static byte[] pngChunk(String type, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + data.length).order(ByteOrder.BIG_ENDIAN);
        byte[] t = ascii(type);
        b.putInt(data.length).put(t).put(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    /** A PNG with text, XMP, eXIf (GPS), tIME and a private chunk after IHDR, and bytes after IEND. */
    public static byte[] pngWithGps(int w, int h, int orientation) {
        byte[] plain = encode(picture(w, h), "png");
        int ihdrEnd = 8 + 12 + 13;
        return concat(java.util.Arrays.copyOfRange(plain, 0, ihdrEnd),
                pngChunk("tEXt", ascii("Comment\0shot at 48.8582N 2.2945E with " + MODEL)),
                pngChunk("iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0<x:xmpmeta><tiff:Make>" + MAKE + "</tiff:Make></x:xmpmeta>")),
                pngChunk("eXIf", exifTiff(orientation)),
                pngChunk("tIME", new byte[]{0x07, (byte) 0xEA, 11, 24, 10, 30, 0}),
                pngChunk("prVt", ascii("device-serial-12345")),
                java.util.Arrays.copyOfRange(plain, ihdrEnd, plain.length),
                ascii("trailing " + MAKE));
    }

    /** A GIF with a comment extension and an XMP application extension, and bytes after the trailer. */
    public static byte[] gifWithMetadata(int w, int h) {
        byte[] plain = encode(picture(w, h), "gif");
        int packed = plain[10] & 0xFF;
        int afterGct = 13 + ((packed & 0x80) != 0 ? 3 * (1 << ((packed & 7) + 1)) : 0);
        byte[] text = ascii("made with " + MODEL + "!");
        byte[] comment = concat(new byte[]{0x21, (byte) 0xFE, (byte) text.length}, text, new byte[]{0});
        byte[] xmpBody = ascii("<x:xmpmeta>" + MAKE + "</x:xmpmeta>");
        byte[] xmp = concat(new byte[]{0x21, (byte) 0xFF, 11}, ascii("XMP DataXMP"), new byte[]{(byte) xmpBody.length}, xmpBody, new byte[]{0});
        return concat(java.util.Arrays.copyOfRange(plain, 0, afterGct), comment, xmp,
                java.util.Arrays.copyOfRange(plain, afterGct, plain.length), ascii(MAKE));
    }

    /** A 1x1 lossless WebP (VP8L) wrapped in VP8X with ICCP, EXIF (GPS) and XMP chunks. Not decodable by the JDK. */
    public static byte[] webpWithGps(int orientation) {
        byte[] vp8l = java.util.Base64.getDecoder().decode("LwAAAAAHEBERiIj+BwA=");
        byte[] vp8x = {0x2C, 0, 0, 0, 0, 0, 0, 0, 0, 0}; // ICC 0x20 | EXIF 0x08 | XMP 0x04, canvas 1x1
        byte[] body = concat(riffChunk("VP8X", vp8x), riffChunk("ICCP", ascii("fake-icc-profile")), riffChunk("VP8L", vp8l),
                riffChunk("EXIF", exifTiff(orientation)), riffChunk("XMP ", ascii("<x:xmpmeta>" + MAKE + "</x:xmpmeta>")));
        ByteBuffer b = ByteBuffer.allocate(12 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(ascii("RIFF")).putInt(4 + body.length).put(ascii("WEBP")).put(body);
        return concat(b.array(), ascii("trailing " + MODEL));
    }

    static byte[] riffChunk(String type, byte[] data) {
        int padded = data.length + (data.length & 1);
        ByteBuffer b = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        b.put(ascii(type)).putInt(data.length).put(data);
        return b.array();
    }
}
