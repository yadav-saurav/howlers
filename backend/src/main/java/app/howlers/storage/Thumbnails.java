package app.howlers.storage;

import java.util.List;
import java.util.function.IntFunction;

/**
 * URLs of the resized variants of an image (D-024). A field is null when that variant doesn't exist (small or
 * older images, GIF, WebP); the original URL always works, so clients fall back to it.
 */
public record Thumbnails(String w320, String w960) {

    /** From the stored {@code variant_widths} ("320,960"); null when there are none. */
    public static Thumbnails of(String variantWidths, IntFunction<String> urlForWidth) {
        if (variantWidths == null || variantWidths.isBlank()) return null;
        String w320 = null;
        String w960 = null;
        for (String part : variantWidths.split(",")) {
            switch (part.trim()) {
                case "320" -> w320 = urlForWidth.apply(320);
                case "960" -> w960 = urlForWidth.apply(960);
                default -> { }
            }
        }
        return w320 == null && w960 == null ? null : new Thumbnails(w320, w960);
    }

    /** "320,960" for the stored column, or null when no variant was made. */
    public static String widths(List<ImageVariants.Variant> variants) {
        if (variants.isEmpty()) return null;
        return String.join(",", variants.stream().map(v -> Integer.toString(v.width())).toList());
    }
}
