package app.howlers.antivirus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Talks to a real clamd. Skipped unless {@code -Dclamav.live.port=<port>} (and optionally {@code -Dclamav.live.host})
 * is given, e.g. against the compose {@code clamav} service published on a local port. See docs/DEPLOYMENT.md.
 *
 * <p>The EICAR test string is stored reversed and assembled in memory, so no antivirus on a developer machine
 * quarantines this source file or the compiled class.
 */
class ClamAvLiveTest {
    private static final String EICAR_REVERSED = "*H+H$!ELIF-TSET-SURIVITNA-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X";

    static byte[] eicar() {
        return new StringBuilder(EICAR_REVERSED).reverse().toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void realClamdDetectsEicarAndPassesACleanFile() throws Exception {
        String port = System.getProperty("clamav.live.port");
        assumeTrue(port != null && !port.isBlank(), "set -Dclamav.live.port to run against a real clamd");
        String host = System.getProperty("clamav.live.host", "127.0.0.1");
        ClamAvClient client = new ClamAvClient(host, Integer.parseInt(port), Duration.ofSeconds(5), Duration.ofSeconds(60), 64 * 1024);

        assertThat(client.ping()).isTrue();
        String version = client.version();
        System.out.println("clamd VERSION: " + version);

        assertThat(eicar()).hasSize(68);
        ClamAvClient.Reply infected = client.scan(new ByteArrayInputStream(eicar()));
        System.out.println("EICAR -> " + infected.raw());
        assertThat(infected.infected()).isTrue();

        byte[] clean = new byte[3 * 1024 * 1024 + 17]; // several INSTREAM chunks
        new Random(42).nextBytes(clean);
        ClamAvClient.Reply ok = client.scan(new ByteArrayInputStream(clean));
        System.out.println("3 MiB random file -> " + ok.raw());
        assertThat(ok.infected()).isFalse();

        // Above clamd's default 25 MB StreamMaxLength: passes only with deploy/clamav/clamd.conf's raised limits.
        byte[] big = new byte[40 * 1024 * 1024];
        new Random(43).nextBytes(big);
        ClamAvClient.Reply okBig = client.scan(new ByteArrayInputStream(big));
        System.out.println("40 MiB random file -> " + okBig.raw());
        assertThat(okBig.infected()).isFalse();

        byte[] text = "Plain meeting notes.\n".getBytes(StandardCharsets.US_ASCII);
        ClamAvClient.Reply okText = client.scan(new ByteArrayInputStream(text));
        System.out.println("text file -> " + okText.raw());
        assertThat(okText.infected()).isFalse();
    }
}
