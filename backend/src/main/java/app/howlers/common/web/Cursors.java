package app.howlers.common.web;

import app.howlers.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursors: "{sortValue}|{id}" base64url-encoded. The sort value is numeric
 * (rank, score or epoch millis) so it compares in SQL without parsing ambiguity.
 */
public final class Cursors {
    private Cursors() {}

    public record Key(double value, long id) {}

    public static String encode(double value, long id) {
        String raw = value + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Offset cursors where keyset ordering is not stable (relevance, rising). */
    public static String encodeOffset(int offset) {
        return encode(offset, 0);
    }

    public static Key decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            return new Key(Double.parseDouble(raw.substring(0, sep)), Long.parseLong(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid cursor");
        }
    }

    public static int decodeOffset(String cursor) {
        Key k = decode(cursor);
        return k == null ? 0 : Math.max(0, Math.min(10_000, (int) k.value()));
    }

    /** Exact microsecond epoch (fits a double losslessly until year 2255). */
    public static long micros(java.time.Instant t) {
        return t.getEpochSecond() * 1_000_000L + t.getNano() / 1_000;
    }

    public static int clampLimit(Integer limit, int def, int max) {
        if (limit == null || limit <= 0) return def;
        return Math.min(limit, max);
    }
}
