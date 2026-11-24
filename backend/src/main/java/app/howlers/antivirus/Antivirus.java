package app.howlers.antivirus;

import app.howlers.common.error.ApiException;
import app.howlers.common.metrics.HowlersMetrics;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Optional malware scanning of uploads with ClamAV (D-029). Off by default ({@code howlers.files.antivirus=none}); then
 * only the structural checks run and the UI says "Checked, not an antivirus scan".
 *
 * <p>With {@code clamav}, private files, podcast media and images are streamed to clamd after the structural checks.
 * When clamd can't be reached, {@code howlers.files.antivirus-required} decides: refuse the upload (fail closed) or accept
 * it as structurally checked only (fail open), and the file then shows "Checked, not an antivirus scan".
 */
@Component
public class Antivirus {
    private static final Logger log = LoggerFactory.getLogger(Antivirus.class);

    public enum Outcome { CLEAN, INFECTED, UNAVAILABLE }

    public record Verdict(Outcome outcome, String signature, String detail) {}

    /** Opens the content to scan; called once per scan. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    private final AntivirusProperties props;
    private final HowlersMetrics metrics;
    private final ClamAvClient client;

    public Antivirus(AntivirusProperties props, HowlersMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        AntivirusProperties.Clamav c = props.clamav();
        this.client = props.clamavEnabled()
                ? new ClamAvClient(c.host(), c.port(), c.connectTimeout(), c.readTimeout(), c.chunkKb() * 1024)
                : null;
        if (client != null) {
            log.info("Antivirus: ClamAV at {}:{} ({})", c.host(), c.port(),
                    props.antivirusRequired() ? "required: uploads are refused while clamd is unreachable"
                            : "optional: uploads fall back to structural checks while clamd is unreachable");
        }
    }

    public boolean enabled() {
        return client != null;
    }

    public boolean required() {
        return props.antivirusRequired();
    }

    /** clamd's version line, or null when disabled or unreachable (for the admin/health view and logs). */
    public String version() {
        return client == null ? null : client.version();
    }

    public Verdict scan(Content content) {
        if (client == null) return new Verdict(Outcome.UNAVAILABLE, null, "Antivirus scanning is not configured.");
        try (InputStream in = content.open()) {
            ClamAvClient.Reply r = client.scan(in);
            metrics.antivirusScan(r.infected() ? "infected" : "clean");
            return r.infected() ? new Verdict(Outcome.INFECTED, r.signature(), r.raw()) : new Verdict(Outcome.CLEAN, null, r.raw());
        } catch (IOException e) {
            metrics.antivirusScan("unavailable");
            log.warn("Antivirus scan not completed: {}", e.getMessage());
            return new Verdict(Outcome.UNAVAILABLE, null, e.getMessage());
        }
    }

    /**
     * For uploads that have no scan record of their own (images, podcast media): throws when the content is infected,
     * or when clamd is unavailable and scanning is required. Does nothing when scanning is off.
     *
     * @return true when ClamAV scanned the content and found nothing
     */
    public boolean check(Content content, String what) {
        if (client == null) return false;
        Verdict v = scan(content);
        return switch (v.outcome()) {
            case CLEAN -> true;
            case INFECTED -> throw ApiException.field("file", infectedMessage(v.signature(), what));
            case UNAVAILABLE -> {
                if (required()) throw unavailable();
                yield false;
            }
        };
    }

    public boolean check(byte[] bytes, String what) {
        return check(() -> new ByteArrayInputStream(bytes), what);
    }

    public static String infectedMessage(String signature, String what) {
        return "The antivirus scanner flagged this " + what + " (" + signature + "), so it wasn't uploaded.";
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANTIVIRUS_UNAVAILABLE",
                "Uploads are paused because the antivirus scanner isn't reachable. Try again in a few minutes.");
    }
}
