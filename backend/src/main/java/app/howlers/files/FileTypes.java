package app.howlers.files;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Identifies file formats from their leading bytes ("magic numbers"), never from the client's Content-Type.
 * Each family lists the extensions it may carry and the MIME type served for each, so a mismatch between the
 * name and the content is detectable.
 */
public final class FileTypes {
    private FileTypes() {}

    /** A recognised family: which extensions are consistent with it and the MIME type for each. */
    public record Family(String label, Map<String, String> types, String defaultType) {
        public boolean accepts(String ext) {
            return ext.isEmpty() || types.containsKey(ext);
        }

        public String contentTypeFor(String ext) {
            return types.getOrDefault(ext, defaultType);
        }
    }

    private record Rule(Predicate<byte[]> matches, Family family) {}

    /** Executables, installers and scripts. Rejected by extension, wherever they appear. */
    public static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "so", "dylib", "sys", "drv", "ocx", "cpl", "scr", "pif", "com", "msi", "msp", "mst", "msix", "appx",
            "bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "mjs", "cjs", "wsf", "wsh", "hta", "lnk", "reg", "inf",
            "sh", "bash", "zsh", "ksh", "csh", "fish", "command", "run", "bin", "elf", "app", "ipa", "apk", "aab", "xapk", "dex",
            "jar", "war", "ear", "class", "py", "pyc", "pyw", "pl", "rb", "php", "phtml", "cgi", "asp", "aspx", "jsp",
            "deb", "rpm", "pkg", "dmg", "docm", "dotm", "xlsm", "xltm", "xlam", "pptm", "potm", "ppam", "ppsm", "sldm");

    /** Executable formats inside archives that are refused (Windows double-click risks plus Java archives). */
    public static final Set<String> BLOCKED_IN_ARCHIVES = Set.of(
            "exe", "scr", "pif", "com", "msi", "bat", "cmd", "ps1", "vbs", "vbe", "js", "jse", "wsf", "hta", "lnk", "jar", "cpl");

    /** Text formats accepted when the content is valid UTF-8 without NUL bytes (text has no magic number). */
    public static final Map<String, String> TEXT_TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"), Map.entry("text", "text/plain"), Map.entry("log", "text/plain"),
            Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"),
            Map.entry("csv", "text/csv"), Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            Map.entry("yaml", "application/yaml"), Map.entry("yml", "application/yaml"), Map.entry("toml", "text/plain"),
            Map.entry("ini", "text/plain"), Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("svg", "image/svg+xml"), Map.entry("css", "text/css"), Map.entry("ics", "text/calendar"),
            Map.entry("vtt", "text/vtt"), Map.entry("srt", "text/plain"), Map.entry("tex", "text/plain"),
            Map.entry("rst", "text/plain"), Map.entry("sql", "text/plain"));

    /** Rendered by browsers as active content, so they're only ever served as attachments (we do that for all files). */
    public static final Set<String> ACTIVE_CONTENT = Set.of("text/html", "image/svg+xml", "application/xml", "text/xml");

    private static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    private static Family f(String label, String defaultType, String... kv) {
        return new Family(label, m(kv), defaultType);
    }

    public static final Family ZIP = f("ZIP archive", "application/zip",
            "zip", "application/zip",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "odt", "application/vnd.oasis.opendocument.text",
            "ods", "application/vnd.oasis.opendocument.spreadsheet",
            "odp", "application/vnd.oasis.opendocument.presentation",
            "epub", "application/epub+zip");

    private static final List<Rule> RULES = List.of(
            new Rule(h -> starts(h, "%PDF-"), f("PDF", "application/pdf", "pdf", "application/pdf")),
            new Rule(h -> bytes(h, 0, 0x89, 'P', 'N', 'G'), f("PNG image", "image/png", "png", "image/png")),
            new Rule(h -> bytes(h, 0, 0xFF, 0xD8, 0xFF), f("JPEG image", "image/jpeg", "jpg", "image/jpeg", "jpeg", "image/jpeg", "jfif", "image/jpeg")),
            new Rule(h -> starts(h, "GIF87a") || starts(h, "GIF89a"), f("GIF image", "image/gif", "gif", "image/gif")),
            new Rule(h -> starts(h, "RIFF") && at(h, 8, "WEBP"), f("WebP image", "image/webp", "webp", "image/webp")),
            new Rule(h -> bytes(h, 0, 'I', 'I', 0x2A, 0x00) || bytes(h, 0, 'M', 'M', 0x00, 0x2A),
                    f("TIFF image", "image/tiff", "tif", "image/tiff", "tiff", "image/tiff")),
            new Rule(h -> at(h, 4, "ftyp") && (at(h, 8, "heic") || at(h, 8, "heix") || at(h, 8, "mif1") || at(h, 8, "heif")),
                    f("HEIC image", "image/heic", "heic", "image/heic", "heif", "image/heif")),
            new Rule(h -> at(h, 4, "ftyp") && (at(h, 8, "avif") || at(h, 8, "avis")), f("AVIF image", "image/avif", "avif", "image/avif")),
            new Rule(h -> at(h, 4, "ftyp") && (at(h, 8, "M4A ") || at(h, 8, "M4B ")),
                    f("M4A audio", "audio/mp4", "m4a", "audio/mp4", "m4b", "audio/mp4", "mp4", "audio/mp4")),
            new Rule(h -> at(h, 4, "ftyp") && at(h, 8, "qt  "), f("QuickTime video", "video/quicktime", "mov", "video/quicktime")),
            new Rule(h -> at(h, 4, "ftyp"), f("MP4 video", "video/mp4", "mp4", "video/mp4", "m4v", "video/mp4",
                    "mov", "video/quicktime", "3gp", "video/3gpp")),
            new Rule(h -> starts(h, "ID3") || (h.length > 1 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0),
                    f("MP3 audio", "audio/mpeg", "mp3", "audio/mpeg", "aac", "audio/aac")),
            new Rule(h -> starts(h, "OggS"), f("Ogg media", "audio/ogg", "ogg", "audio/ogg", "oga", "audio/ogg", "opus", "audio/ogg", "ogv", "video/ogg")),
            new Rule(h -> starts(h, "RIFF") && at(h, 8, "WAVE"), f("WAV audio", "audio/wav", "wav", "audio/wav")),
            new Rule(h -> starts(h, "RIFF") && at(h, 8, "AVI "), f("AVI video", "video/x-msvideo", "avi", "video/x-msvideo")),
            new Rule(h -> starts(h, "fLaC"), f("FLAC audio", "audio/flac", "flac", "audio/flac")),
            new Rule(h -> bytes(h, 0, 0x1A, 0x45, 0xDF, 0xA3), f("WebM/Matroska video", "video/webm",
                    "webm", "video/webm", "mkv", "video/x-matroska", "mka", "audio/x-matroska")),
            new Rule(h -> bytes(h, 0, 'P', 'K', 3, 4) || bytes(h, 0, 'P', 'K', 5, 6) || bytes(h, 0, 'P', 'K', 7, 8), ZIP),
            new Rule(h -> bytes(h, 0, 0x1F, 0x8B), f("Gzip archive", "application/gzip", "gz", "application/gzip", "tgz", "application/gzip")),
            new Rule(h -> bytes(h, 0, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C), f("7-Zip archive", "application/x-7z-compressed", "7z", "application/x-7z-compressed")),
            new Rule(h -> starts(h, "Rar!\u001A\u0007"), f("RAR archive", "application/vnd.rar", "rar", "application/vnd.rar")),
            new Rule(h -> starts(h, "BZh"), f("Bzip2 archive", "application/x-bzip2", "bz2", "application/x-bzip2", "tbz2", "application/x-bzip2")),
            new Rule(h -> bytes(h, 0, 0xFD, '7', 'z', 'X', 'Z', 0x00), f("XZ archive", "application/x-xz", "xz", "application/x-xz", "txz", "application/x-xz")),
            new Rule(h -> at(h, 257, "ustar"), f("TAR archive", "application/x-tar", "tar", "application/x-tar")),
            new Rule(h -> bytes(h, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1), f("Legacy Office document", "application/x-ole-storage",
                    "doc", "application/msword", "xls", "application/vnd.ms-excel", "ppt", "application/vnd.ms-powerpoint",
                    "msg", "application/vnd.ms-outlook")),
            new Rule(h -> starts(h, "{\\rtf"), f("RTF document", "application/rtf", "rtf", "application/rtf")));

    /** Executable or script content, regardless of the file name. */
    public static String executableKind(byte[] h) {
        if (bytes(h, 0, 'M', 'Z')) return "a Windows executable";
        if (bytes(h, 0, 0x7F, 'E', 'L', 'F')) return "a Linux executable";
        if (bytes(h, 0, 0xFE, 0xED, 0xFA, 0xCE) || bytes(h, 0, 0xFE, 0xED, 0xFA, 0xCF)
                || bytes(h, 0, 0xCE, 0xFA, 0xED, 0xFE) || bytes(h, 0, 0xCF, 0xFA, 0xED, 0xFE)) return "a macOS executable";
        if (bytes(h, 0, 0xCA, 0xFE, 0xBA, 0xBE)) return "an executable or Java class";
        if (starts(h, "dex\n")) return "an Android executable";
        if (starts(h, "#!")) return "a script";
        return null;
    }

    /** The binary family whose magic bytes match, or null. */
    public static Family sniff(byte[] head) {
        for (Rule r : RULES) if (r.matches().test(head)) return r.family();
        return null;
    }

    /** True when the sample is valid UTF-8 (a truncated final character is fine) without NUL or most control bytes. */
    public static boolean looksLikeText(byte[] head, boolean truncated) {
        int controls = 0;
        for (byte b : head) {
            int c = b & 0xFF;
            if (c == 0) return false;
            if (c < 0x09 || (c > 0x0D && c < 0x20 && c != 0x1B)) controls++;
        }
        if (controls > head.length / 100 + 1) return false;
        int len = head.length;
        if (truncated) {
            // Drop a possibly cut multi-byte sequence at the end of the sample.
            int back = 0;
            while (back < 3 && len - back - 1 >= 0 && (head[len - back - 1] & 0xC0) == 0x80) back++;
            if (len - back - 1 >= 0 && (head[len - back - 1] & 0xC0) == 0xC0) len = len - back - 1;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(head, 0, len));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean starts(byte[] h, String s) {
        return at(h, 0, s);
    }

    private static boolean at(byte[] h, int off, String s) {
        if (h.length < off + s.length()) return false;
        for (int i = 0; i < s.length(); i++) if ((h[off + i] & 0xFF) != s.charAt(i)) return false;
        return true;
    }

    private static boolean bytes(byte[] h, int off, int... v) {
        if (h.length < off + v.length) return false;
        for (int i = 0; i < v.length; i++) if ((h[off + i] & 0xFF) != v[i]) return false;
        return true;
    }
}
