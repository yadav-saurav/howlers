package app.howlers.files;

import app.howlers.antivirus.Antivirus;
import java.nio.file.Path;

/**
 * Inspects an uploaded file after it has been streamed to storage. Implementations decide CLEAN, REJECTED or
 * UNSCANNED and, for accepted plaintext files, the MIME type the server will serve.
 *
 * <p>{@link DefaultFileScanner} does structural checks only: magic bytes vs extension, an executable/script denylist
 * and archive bomb heuristics. It is <b>not</b> an antivirus engine, and the UI never calls it one.
 * {@link LayeredFileScanner} (the bean in use) runs those checks first and then, when configured, ClamAV (D-029).
 */
public interface FileScanner {

    enum Status { CLEAN, REJECTED, UNSCANNED }

    /** STRUCTURAL = type and structure checks only; CLAMAV = those plus a ClamAV scan that found nothing. */
    enum Engine { STRUCTURAL, CLAMAV }

    /**
     * @param name    the sanitised file name the user gave
     * @param head    the first bytes of the file (up to 8 KiB)
     * @param size    total size in bytes
     * @param local   the stored file on local disk when the storage driver has one, else null (deep checks skipped)
     * @param e2ee    true for end-to-end encrypted uploads: the server only sees ciphertext and can't inspect content
     * @param content opens the stored bytes for a full scan (antivirus); null when not available
     */
    record Input(String name, byte[] head, long size, Path local, boolean e2ee, Antivirus.Content content) {
        public Input(String name, byte[] head, long size, Path local, boolean e2ee) {
            this(name, head, size, local, e2ee, null);
        }
    }

    /** contentType is set when the file is accepted; reason explains a rejection (or what was checked). */
    record Result(Status status, String contentType, String reason, Engine engine) {
        public Result(Status status, String contentType, String reason) {
            this(status, contentType, reason, Engine.STRUCTURAL);
        }

        public static Result rejected(String reason) {
            return new Result(Status.REJECTED, null, reason);
        }
    }

    Result scan(Input input);
}
