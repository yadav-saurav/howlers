package app.howlers.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageMetadataTest {

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private static BufferedImage decode(byte[] b) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
        assertThat(img).isNotNull();
        return img;
    }

    private static void assertSamePixels(BufferedImage a, BufferedImage b) {
        assertThat(b.getWidth()).isEqualTo(a.getWidth());
        assertThat(b.getHeight()).isEqualTo(a.getHeight());
        assertThat(b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()))
                .isEqualTo(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()));
    }

    /** Leaks we planted: camera make/model, GPS text, XMP, IPTC city, comment, trailing data. */
    private static void assertNoLeaks(byte[] stripped) {
        String s = latin1(stripped);
        assertThat(s).doesNotContain(ImageFixtures.MAKE, ImageFixtures.MODEL, "xmpmeta", "Paris", "Photoshop", "48.8582", "taken at home",
                "device-serial", "trailing");
    }

    /** APPn markers of a JPEG header and their payload prefixes. */
    private static List<String> jpegSegments(byte[] b) {
        List<String> out = new ArrayList<>();
        int i = 2;
        while (i + 4 < b.length && (b[i] & 0xFF) == 0xFF) {
            int m = b[i + 1] & 0xFF;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            String id = latin1(java.util.Arrays.copyOfRange(b, i + 4, Math.min(i + 2 + len, i + 10)));
            out.add(String.format("%02X:%s", m, id));
            if (m == 0xDA) break;
            i += 2 + len;
        }
        return out;
    }

    @Test
    void jpegLosesGpsAndCameraDataButKeepsPixelsAndOrientation() throws Exception {
        byte[] original = ImageFixtures.jpegWithGps(64, 40, 6);
        assertThat(latin1(original)).contains(ImageFixtures.MAKE, "xmpmeta", "Paris");
        assertThat(ImageVariants.jpegOrientation(original)).isEqualTo(6);

        byte[] stripped = ImageMetadata.strip(original, "jpg");

        assertNoLeaks(stripped);
        // Only the orientation survives, in a minimal EXIF block: no GPS IFD, no make/model.
        assertThat(ImageVariants.jpegOrientation(stripped)).isEqualTo(6);
        List<String> app1 = jpegSegments(stripped).stream().filter(s -> s.startsWith("E1")).toList();
        assertThat(app1).hasSize(1);
        int exifAt = latin1(stripped).indexOf("Exif\0\0");
        byte[] tiff = java.util.Arrays.copyOfRange(stripped, exifAt + 6, exifAt + 6 + 26);
        assertThat(tiff).isEqualTo(ImageMetadata.orientationTiff(6));
        assertThat(jpegSegments(stripped)).noneMatch(s -> s.startsWith("ED") || s.startsWith("FE"));
        // Pixels untouched: the decoder sees the same image (ImageIO ignores the orientation tag).
        assertSamePixels(decode(original), decode(stripped));
        assertThat(stripped[stripped.length - 2] & 0xFF).isEqualTo(0xFF);
        assertThat(stripped[stripped.length - 1] & 0xFF).isEqualTo(0xD9);
        // Idempotent.
        assertThat(ImageMetadata.strip(stripped, "jpg")).isEqualTo(stripped);
    }

    @Test
    void uprightJpegGetsNoExifAtAll() throws Exception {
        byte[] stripped = ImageMetadata.strip(ImageFixtures.jpegWithGps(50, 30, 1), "jpg");
        assertThat(latin1(stripped)).doesNotContain("Exif");
        assertThat(decode(stripped).getWidth()).isEqualTo(50);
        assertThat(decode(stripped).getHeight()).isEqualTo(30);
    }

    @Test
    void jpegKeepsIccProfileAndAdobeSegments() {
        byte[] plain = ImageFixtures.encode(ImageFixtures.picture(8, 8), "jpg");
        int app0End = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        byte[] icc = seg(0xE2, "ICC_PROFILE\0\u0001\u0001fake");
        byte[] mpf = seg(0xE2, "MPF\0" + ImageFixtures.MAKE);
        byte[] adobe = seg(0xEE, "Adobe\0d\0\0\0\0\u0001");
        byte[] ducky = seg(0xEC, "Ducky" + ImageFixtures.MODEL);
        byte[] jpeg = join(java.util.Arrays.copyOfRange(plain, 0, app0End), icc, mpf, adobe, ducky,
                java.util.Arrays.copyOfRange(plain, app0End, plain.length));
        byte[] stripped = ImageMetadata.strip(jpeg, "jpg");
        assertThat(jpegSegments(stripped)).anyMatch(x -> x.startsWith("E0:JFIF\0")).contains("E2:ICC_PR", "EE:Adobe\0");
        assertThat(latin1(stripped)).doesNotContain("MPF", ImageFixtures.MAKE, ImageFixtures.MODEL);
    }

    @Test
    void pngDropsTextXmpExifTimeAndPrivateChunks() throws Exception {
        byte[] original = ImageFixtures.pngWithGps(30, 20, 6);
        byte[] stripped = ImageMetadata.strip(original, "png");
        assertNoLeaks(stripped);
        List<String> types = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(stripped).order(ByteOrder.BIG_ENDIAN);
        int i = 8;
        while (i < stripped.length) {
            int len = b.getInt(i);
            String type = latin1(java.util.Arrays.copyOfRange(stripped, i + 4, i + 8));
            CRC32 crc = new CRC32();
            crc.update(stripped, i + 4, 4 + len);
            assertThat((int) crc.getValue()).as("CRC of " + type).isEqualTo(b.getInt(i + 8 + len));
            types.add(type);
            if (type.equals("eXIf")) {
                assertThat(java.util.Arrays.copyOfRange(stripped, i + 8, i + 8 + len)).isEqualTo(ImageMetadata.orientationTiff(6));
            }
            i += 12 + len;
        }
        assertThat(types).doesNotContain("tEXt", "iTXt", "zTXt", "tIME", "prVt");
        assertThat(types).containsSubsequence("IHDR", "eXIf", "IDAT", "IEND");
        assertThat(types.get(types.size() - 1)).isEqualTo("IEND");
        assertSamePixels(decode(original), decode(stripped));
    }

    @Test
    void gifDropsCommentsAndXmpAndKeepsFrames() throws Exception {
        byte[] original = ImageFixtures.gifWithMetadata(12, 9);
        assertThat(latin1(original)).contains(ImageFixtures.MODEL, "XMP DataXMP");
        byte[] stripped = ImageMetadata.strip(original, "gif");
        assertThat(latin1(stripped)).doesNotContain(ImageFixtures.MODEL, ImageFixtures.MAKE, "XMP DataXMP");
        assertThat(stripped[stripped.length - 1]).isEqualTo((byte) 0x3B);
        assertSamePixels(decode(original), decode(stripped));
    }

    @Test
    void webpDropsExifAndXmpChunksAndFixesHeader() {
        byte[] original = ImageFixtures.webpWithGps(3);
        byte[] stripped = ImageMetadata.strip(original, "webp");
        String s = latin1(stripped);
        assertThat(s).doesNotContain(ImageFixtures.MAKE, ImageFixtures.MODEL, "XMP ", "trailing");
        assertThat(s).contains("VP8X", "VP8L", "ICCP", "fake-icc-profile");
        ByteBuffer b = ByteBuffer.wrap(stripped).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getInt(4)).isEqualTo(stripped.length - 8);
        assertThat(stripped[20] & 0xFF).isEqualTo(0x20 | 0x08); // ICC kept, EXIF (orientation only) kept, XMP cleared
        int exif = s.indexOf("EXIF");
        assertThat(b.getInt(exif + 4)).isEqualTo(26);
        assertThat(java.util.Arrays.copyOfRange(stripped, exif + 8, exif + 8 + 26)).isEqualTo(ImageMetadata.orientationTiff(3));
        // Upright: no EXIF chunk and the flag is cleared.
        byte[] upright = ImageMetadata.strip(ImageFixtures.webpWithGps(1), "webp");
        assertThat(latin1(upright)).doesNotContain("EXIF");
        assertThat(upright[20] & 0xFF).isEqualTo(0x20);
    }

    @Test
    void malformedContainersAreRefused() {
        assertThatThrownBy(() -> ImageMetadata.strip(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x7F, 1}, "jpg"))
                .isInstanceOf(ImageMetadata.MalformedImageException.class);
        byte[] png = ImageFixtures.pngWithGps(4, 4, 1);
        assertThatThrownBy(() -> ImageMetadata.strip(java.util.Arrays.copyOf(png, 40), "png"))
                .isInstanceOf(ImageMetadata.MalformedImageException.class);
        assertThatThrownBy(() -> ImageMetadata.strip("GIF89a".getBytes(StandardCharsets.US_ASCII), "gif"))
                .isInstanceOf(ImageMetadata.MalformedImageException.class);
    }

    @Test
    void variantsAreStillUprightFromTheStrippedOriginal() throws Exception {
        byte[] stripped = ImageMetadata.strip(ImageFixtures.jpegWithGps(1200, 400, 6), "jpg");
        ImageInspector.Decoded d = ImageInspector.decode(stripped);
        List<ImageVariants.Variant> vs = ImageVariants.generate(stripped, d);
        assertThat(vs).isNotEmpty();
        // Orientation 6 rotates 1200x400 to 400x1200, so only the 320 variant exists, and it is portrait.
        assertThat(vs.get(0).width()).isEqualTo(320);
        assertThat(vs.get(0).height()).isEqualTo(960);
    }

    private static byte[] seg(int marker, String payload) {
        byte[] p = payload.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[p.length + 4];
        out[0] = (byte) 0xFF;
        out[1] = (byte) marker;
        out[2] = (byte) ((p.length + 2) >> 8);
        out[3] = (byte) (p.length + 2);
        System.arraycopy(p, 0, out, 4, p.length);
        return out;
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
}
