package app.howlers.storage;

import app.howlers.common.error.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Validates uploads by their magic bytes — never by the client-supplied filename or Content-Type —
 * and extracts dimensions where the JDK can decode the format.
 */
public final class ImageInspector {
    private ImageInspector() {}

    public record ImageInfo(String contentType, String extension, Integer width, Integer height) {}

    /** The validated info plus the decoded pixels (null for WebP, which the JDK can't decode), so callers decode once. */
    public record Decoded(ImageInfo info, BufferedImage image) {}

    public static ImageInfo inspect(byte[] b) {
        return decode(b).info();
    }

    public static Decoded decode(byte[] b) {
        String type;
        String ext;
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            type = "image/png"; ext = "png";
        } else if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            type = "image/jpeg"; ext = "jpg";
        } else if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            type = "image/webp"; ext = "webp";
        } else if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            type = "image/gif"; ext = "gif";
        } else {
            throw ApiException.field("file", "Only PNG, JPEG, WebP and GIF images are supported");
        }
        Integer w = null, h = null;
        BufferedImage img = null;
        if (!"webp".equals(ext)) {
            try {
                img = ImageIO.read(new ByteArrayInputStream(b));
                if (img == null) throw ApiException.field("file", "The image could not be read");
                w = img.getWidth();
                h = img.getHeight();
                if ((long) w * h > 50_000_000L) throw ApiException.field("file", "Image dimensions are too large");
            } catch (IOException e) {
                throw ApiException.field("file", "The image could not be read");
            }
        }
        return new Decoded(new ImageInfo(type, ext, w, h), img);
    }
}
