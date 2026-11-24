package app.howlers.storage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Resized copies of uploaded images (D-024), stored beside the original as {@code <key>_w320.<ext>} and
 * {@code <key>_w960.<ext>}.
 * <ul>
 *   <li>Only PNG and JPEG get variants, written in the same format (PNG keeps transparency). GIFs keep only the
 *       original, so animations are never flattened to one frame. WebP isn't decodable by the JDK, so it has none.</li>
 *   <li>A variant is made only when the original is wider than the target, so no image is ever upscaled.</li>
 *   <li>Variants are re-encoded from pixels, so they carry no EXIF, GPS, XMP or ICC metadata. The JPEG EXIF orientation
 *       is applied first, so phone photos aren't shown sideways once the tag is gone.</li>
 * </ul>
 */
public final class ImageVariants {
    private ImageVariants() {}

    public static final int[] WIDTHS = {320, 960};

    public record Variant(int width, int height, byte[] bytes, String contentType) {}

    /** {@code media/abc.png} + 320 = {@code media/abc_w320.png}. */
    public static String keyFor(String originalKey, int width) {
        int dot = originalKey.lastIndexOf('.');
        return originalKey.substring(0, dot) + "_w" + width + originalKey.substring(dot);
    }

    public static boolean supports(String extension) {
        return "png".equals(extension) || "jpg".equals(extension);
    }

    /** Variants for every target width narrower than the image. Empty for unsupported formats. */
    public static List<Variant> generate(byte[] original, ImageInspector.Decoded decoded) {
        List<Variant> out = new ArrayList<>();
        ImageInspector.ImageInfo info = decoded.info();
        if (!supports(info.extension()) || decoded.image() == null) return out;
        BufferedImage src = decoded.image();
        if ("jpg".equals(info.extension())) src = orient(src, jpegOrientation(original));
        boolean png = "png".equals(info.extension());
        for (int w : WIDTHS) {
            if (src.getWidth() <= w) continue;
            int h = Math.max(1, (int) Math.round(src.getHeight() * (w / (double) src.getWidth())));
            BufferedImage scaled = scale(src, w, h, png && src.getColorModel().hasAlpha());
            out.add(new Variant(w, h, png ? encodePng(scaled) : encodeJpeg(scaled), info.contentType()));
        }
        return out;
    }

    /** Halves the size step by step with bilinear filtering, which looks close to bicubic at a fraction of the cost. */
    static BufferedImage scale(BufferedImage src, int targetW, int targetH, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        do {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            BufferedImage next = new BufferedImage(w, h, type);
            Graphics2D g = next.createGraphics();
            try {
                if (!alpha) {
                    g.setColor(java.awt.Color.WHITE); // JPEG has no alpha; never let transparent pixels turn black
                    g.fillRect(0, 0, w, h);
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(cur, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            cur = next;
        } while (w != targetW || h != targetH);
        return cur;
    }

    private static byte[] encodePng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encodeJpeg(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.82f);
            writer.write(null, new IIOImage(img, null, null), p); // null metadata: no EXIF/GPS in the output
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
    }

    /**
     * EXIF orientation (1-8) from a JPEG's APP1 segment, or 1 when absent or unreadable. Only the IFD0 orientation tag
     * is read; nothing else from the metadata is used.
     */
    public static int jpegOrientation(byte[] b) {
        try {
            int i = 2;
            while (i + 4 < b.length && (b[i] & 0xFF) == 0xFF) {
                int marker = b[i + 1] & 0xFF;
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (marker == 0xDA || marker == 0xD9) break; // start of scan: no more metadata
                if (marker == 0xE1 && len > 16 && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                    int tiff = i + 10;
                    boolean le = b[tiff] == 'I';
                    int ifd = tiff + u32(b, tiff + 4, le);
                    int entries = u16(b, ifd, le);
                    for (int e = 0; e < entries; e++) {
                        int at = ifd + 2 + e * 12;
                        if (at + 12 > b.length) break;
                        if (u16(b, at, le) == 0x0112) {
                            int v = u16(b, at + 8, le);
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                i += 2 + len;
            }
        } catch (RuntimeException ignored) {
            // malformed metadata: treat as upright
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean le) {
        return le ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) : ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static int u32(byte[] b, int at, boolean le) {
        return le ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24)
                : ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** Applies an EXIF orientation so the pixels are upright. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
