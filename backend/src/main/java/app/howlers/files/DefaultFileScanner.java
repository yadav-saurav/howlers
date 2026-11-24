package app.howlers.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Structural checks, honestly named: this catches renamed executables, scripts, type/extension mismatches and
 * archive bombs. It does not detect malware inside otherwise valid documents.
 */
@Component
public class DefaultFileScanner implements FileScanner {
    /** Marker at the start of every Howlers E2EE container ("STRATAE2" + version 1). See frontend crypto.ts. */
    static final byte[] E2EE_MAGIC = {'S', 'T', 'R', 'A', 'T', 'A', 'E', '2', 1};

    static final int MAX_ZIP_ENTRIES = 20_000;
    static final long MAX_ZIP_EXPANDED = 20L * 1024 * 1024 * 1024; // 20 GiB declared
    static final long BOMB_MIN_EXPANDED = 100L * 1024 * 1024;     // ratios only matter above 100 MiB expanded
    static final long MAX_RATIO = 100;

    @Override
    public Result scan(Input in) {
        if (in.e2ee()) {
            if (!startsWith(in.head(), E2EE_MAGIC)) {
                return Result.rejected("This isn't a Howlers encrypted file. Encrypt it in the browser first.");
            }
            return new Result(Status.UNSCANNED, "application/octet-stream",
                    "End-to-end encrypted: the server only has ciphertext, so it can't inspect the content.");
        }
        String ext = FileTypes.extension(in.name());
        if (FileTypes.BLOCKED_EXTENSIONS.contains(ext)) {
            return Result.rejected("." + ext + " files can't be uploaded: executables, installers, scripts and macro-enabled documents are blocked.");
        }
        String exe = FileTypes.executableKind(in.head());
        if (exe != null) return Result.rejected("This file is " + exe + ", which can't be uploaded.");

        FileTypes.Family family = FileTypes.sniff(in.head());
        if (family != null) {
            if (!family.accepts(ext)) {
                return Result.rejected("The file's content (" + family.label() + ") doesn't match its ." + ext + " extension.");
            }
            String type = family.contentTypeFor(ext);
            if (family == FileTypes.ZIP && in.local() != null) {
                String problem = inspectZip(in.local(), in.size());
                if (problem != null) return Result.rejected(problem);
            }
            return new Result(Status.CLEAN, type, "Content matches its type (" + family.label() + ").");
        }
        boolean truncated = in.size() > in.head().length;
        if (FileTypes.looksLikeText(in.head(), truncated)) {
            if (ext.isEmpty()) return new Result(Status.CLEAN, "text/plain", "Plain text.");
            String type = FileTypes.TEXT_TYPES.get(ext);
            if (type != null) return new Result(Status.CLEAN, type, "Text content.");
            return Result.rejected("." + ext + " files aren't supported. Upload documents, images, audio, video, text or archives.");
        }
        return Result.rejected("This file type isn't supported. Upload documents, images, audio, video, text or archives.");
    }

    /** Cheap checks on the central directory: entry count, declared expanded size, compression ratio, executables. */
    static String inspectZip(Path path, long size) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            int count = 0;
            long expanded = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++count > MAX_ZIP_ENTRIES) return "This archive has too many entries (over " + MAX_ZIP_ENTRIES + ").";
                if (!e.isDirectory()) {
                    String ext = FileTypes.extension(e.getName().replace('\\', '/').substring(e.getName().replace('\\', '/').lastIndexOf('/') + 1));
                    if (FileTypes.BLOCKED_IN_ARCHIVES.contains(ext)) {
                        return "This archive contains an executable or script (." + ext + "), which can't be shared here.";
                    }
                }
                long s = e.getSize();
                if (s > 0) expanded += s;
                long c = e.getCompressedSize();
                if (s > BOMB_MIN_EXPANDED && c > 0 && s / c > MAX_RATIO * 10) {
                    return "This archive looks like a decompression bomb (an entry expands over " + (MAX_RATIO * 10) + "×).";
                }
                if (expanded > MAX_ZIP_EXPANDED) return "This archive expands to more than 20 GB.";
            }
            if (expanded > BOMB_MIN_EXPANDED && expanded / Math.max(1, size) > MAX_RATIO) {
                return "This archive looks like a decompression bomb (it expands over " + MAX_RATIO + "× its size).";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "This archive is damaged and couldn't be read.";
        }
    }

    private static boolean startsWith(byte[] h, byte[] prefix) {
        if (h.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (h[i] != prefix[i]) return false;
        return true;
    }
}
