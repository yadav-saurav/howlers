package app.howlers.antivirus;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Antivirus settings, bound from {@code howlers.files.*} next to the other file settings (D-029).
 *
 * @param antivirus         {@code none} (default: structural checks only) or {@code clamav}
 * @param antivirusRequired when clamd can't be reached: {@code true} refuses the upload (fail closed, recommended in
 *                          production), {@code false} accepts it with structural checks only and says so (fail open)
 * @param clamav            clamd connection settings
 */
@ConfigurationProperties(prefix = "howlers.files")
public record AntivirusProperties(String antivirus, boolean antivirusRequired, Clamav clamav) {

    /**
     * @param host           clamd host ({@code CLAMAV_HOST})
     * @param port           clamd TCP port ({@code CLAMAV_PORT}, 3310)
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for each read, including clamd's verdict after the last chunk
     * @param chunkKb        INSTREAM chunk size
     */
    public record Clamav(String host, int port, Duration connectTimeout, Duration readTimeout, int chunkKb) {
        public Clamav {
            if (host == null || host.isBlank()) host = "localhost";
            if (port <= 0) port = 3310;
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) connectTimeout = Duration.ofSeconds(2);
            if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) readTimeout = Duration.ofSeconds(120);
            if (chunkKb <= 0) chunkKb = 64;
        }
    }

    public AntivirusProperties {
        antivirus = antivirus == null || antivirus.isBlank() ? "none" : antivirus.strip().toLowerCase(Locale.ROOT);
        if (!antivirus.equals("none") && !antivirus.equals("clamav")) {
            throw new IllegalArgumentException("howlers.files.antivirus must be none or clamav, not " + antivirus);
        }
        if (clamav == null) clamav = new Clamav(null, 0, null, null, 0);
    }

    public boolean clamavEnabled() {
        return antivirus.equals("clamav");
    }
}
