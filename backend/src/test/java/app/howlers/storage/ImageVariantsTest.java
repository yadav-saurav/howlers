package app.howlers.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageVariantsTest {

    static byte[] png(int w, int h, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 60, 40, alpha ? 128 : 255));
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] gif(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "gif", out);
        return out.toByteArray();
    }

    static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 4, h / 4); // marks the top-left corner
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Inserts a big-endian EXIF APP1 segment with one IFD0 entry: Orientation = {@code orientation}, plus a GPS-ish marker string. */
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,          // header, IFD0 at offset 8
                0, 1,                                  // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, 1, value
                0, 0, 0, 0                             // no next IFD
        };
        byte[] marker = "GPS-SECRET".getBytes(StandardCharsets.US_ASCII);
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int len = 2 + exifHeader.length + tiff.length + marker.length;
        byte[] out = new byte[jpeg.length + 2 + len];
        int i = 0;
        out[i++] = (byte) 0xFF;
        out[i++] = (byte) 0xD8;
        out[i++] = (byte) 0xFF;
        out[i++] = (byte) 0xE1;
        out[i++] = (byte) (len >> 8);
        out[i++] = (byte) len;
        System.arraycopy(exifHeader, 0, out, i, exifHeader.length);
        i += exifHeader.length;
        System.arraycopy(tiff, 0, out, i, tiff.length);
        i += tiff.length;
        System.arraycopy(marker, 0, out, i, marker.length);
        i += marker.length;
        System.arraycopy(jpeg, 2, out, i, jpeg.length - 2);
        return out;
    }

    @Test
    void makesOnlyNarrowerVariantsInTheOriginalFormat() throws Exception {
        byte[] b = png(1500, 750, true);
        List<ImageVariants.Variant> v = ImageVariants.generate(b, ImageInspector.decode(b));
        assertThat(v).extracting(ImageVariants.Variant::width).containsExactly(320, 960);
        assertThat(v).extracting(ImageVariants.Variant::height).containsExactly(160, 480);
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(v.getFirst().bytes()));
        assertThat(small.getWidth()).isEqualTo(320);
        assertThat(small.getColorModel().hasAlpha()).isTrue(); // PNG transparency survives
        assertThat(v.getFirst().contentType()).isEqualTo("image/png");

        byte[] tiny = png(300, 300, false);
        assertThat(ImageVariants.generate(tiny, ImageInspector.decode(tiny))).isEmpty();
        byte[] g = gif(1000, 500);
        assertThat(ImageVariants.generate(g, ImageInspector.decode(g))).isEmpty();
    }

    @Test
    void appliesExifOrientationAndStripsMetadata() throws Exception {
        byte[] original = withExifOrientation(jpeg(1000, 500), 6); // stored landscape, displayed rotated 90° clockwise
        assertThat(ImageVariants.jpegOrientation(original)).isEqualTo(6);
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("GPS-SECRET");

        List<ImageVariants.Variant> v = ImageVariants.generate(original, ImageInspector.decode(original));
        assertThat(v).extracting(ImageVariants.Variant::width).containsExactly(320);
        BufferedImage upright = ImageIO.read(new ByteArrayInputStream(v.getFirst().bytes()));
        assertThat(upright.getHeight()).isGreaterThan(upright.getWidth()); // portrait once rotated
        assertThat(upright.getHeight()).isEqualTo(640);
        // The red corner moved from top-left to top-right, as a 90° clockwise rotation should.
        assertThat(new Color(upright.getRGB(upright.getWidth() - 5, 5)).getRed()).isGreaterThan(200);
        assertThat(new Color(upright.getRGB(5, 5)).getRed()).isLessThan(60);
        String text = new String(v.getFirst().bytes(), StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif").doesNotContain("GPS-SECRET");
    }

    @Test
    void orientationDefaultsToUprightWhenAbsentOrMalformed() throws Exception {
        assertThat(ImageVariants.jpegOrientation(jpeg(10, 10))).isEqualTo(1);
        assertThat(ImageVariants.jpegOrientation(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 40, 'E', 'x'}))
                .isEqualTo(1);
        assertThat(ImageVariants.keyFor("media/abcDEF123456.jpg", 960)).isEqualTo("media/abcDEF123456_w960.jpg");
        assertThat(StorageService.isSafeKey(ImageVariants.keyFor("private/" + "a".repeat(24) + ".png", 320))).isTrue();
    }
}
