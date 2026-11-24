package app.howlers.antivirus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.howlers.common.metrics.HowlersMetrics;
import app.howlers.files.DefaultFileScanner;
import app.howlers.files.FileScanner;
import app.howlers.files.LayeredFileScanner;
import app.howlers.support.FakeClamd;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The INSTREAM client and the layered scanner against a fake clamd socket server. */
class ClamAvClientTest {
    private FakeClamd clamd;

    @BeforeEach
    void start() throws IOException {
        clamd = new FakeClamd();
    }

    @AfterEach
    void stop() throws IOException {
        clamd.close();
    }

    private ClamAvClient client(int chunk) {
        return new ClamAvClient("127.0.0.1", clamd.port(), Duration.ofSeconds(2), Duration.ofSeconds(2), chunk);
    }

    @Test
    void cleanStreamIsOkAndArrivesWholeInChunks() throws Exception {
        byte[] data = new byte[10_000];
        new Random(7).nextBytes(data);
        ClamAvClient.Reply r = client(1000).scan(new ByteArrayInputStream(data));
        assertThat(r.infected()).isFalse();
        assertThat(r.raw()).isEqualTo("stream: OK");
        assertThat(clamd.payloads()).hasSize(1);
        assertThat(Arrays.equals(clamd.payloads().get(0), data)).isTrue();
        assertThat(clamd.chunks()).isEqualTo(10);
        assertThat(clamd.maxChunk()).isEqualTo(1000);
    }

    @Test
    void foundSignatureIsReported() throws Exception {
        byte[] data = ("hello " + FakeClamd.MARKER + " bye").getBytes(StandardCharsets.US_ASCII);
        ClamAvClient.Reply r = client(4).scan(new ByteArrayInputStream(data));
        assertThat(r.infected()).isTrue();
        assertThat(r.signature()).isEqualTo("Eicar-Signature");
    }

    @Test
    void emptyStreamIsScannedToo() throws Exception {
        assertThat(client(64).scan(new ByteArrayInputStream(new byte[0])).infected()).isFalse();
        assertThat(clamd.payloads().get(0)).isEmpty();
    }

    @Test
    void unreachableHungAndRefusingServersFail() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        ClamAvClient nobody = new ClamAvClient("127.0.0.1", closedPort, Duration.ofMillis(500), Duration.ofMillis(500), 64);
        assertThatThrownBy(() -> nobody.scan(new ByteArrayInputStream(new byte[10])))
                .isInstanceOf(ClamAvClient.ClamAvException.class).hasMessageContaining("unreachable");
        assertThat(nobody.ping()).isFalse();

        clamd.mode(FakeClamd.Mode.NEVER_REPLY);
        long t0 = System.nanoTime();
        ClamAvClient impatient = new ClamAvClient("127.0.0.1", clamd.port(), Duration.ofSeconds(1), Duration.ofMillis(400), 64);
        assertThatThrownBy(() -> impatient.scan(new ByteArrayInputStream(new byte[10]))).isInstanceOf(ClamAvClient.ClamAvException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5)); // the read timeout applied

        clamd.mode(FakeClamd.Mode.SIZE_LIMIT);
        assertThatThrownBy(() -> client(64).scan(new ByteArrayInputStream(new byte[100_000])))
                .isInstanceOf(ClamAvClient.ClamAvException.class).hasMessageContaining("size limit");

        clamd.mode(FakeClamd.Mode.CLOSE_IMMEDIATELY);
        assertThatThrownBy(() -> client(64).scan(new ByteArrayInputStream(new byte[10]))).isInstanceOf(ClamAvClient.ClamAvException.class);
    }

    @Test
    void pingAndVersion() {
        assertThat(client(64).ping()).isTrue();
        assertThat(client(64).version()).startsWith("ClamAV");
    }

    @Test
    void parsesRealClamdReplies() throws Exception {
        assertThat(ClamAvClient.parse("stream: OK").infected()).isFalse();
        assertThat(ClamAvClient.parse("stream: Win.Test.EICAR_HDB-1 FOUND").signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThatThrownBy(() -> ClamAvClient.parse("INSTREAM size limit exceeded. ERROR")).isInstanceOf(ClamAvClient.ClamAvException.class);
        assertThatThrownBy(() -> ClamAvClient.parse("garbage")).isInstanceOf(ClamAvClient.ClamAvException.class);
    }

    // ------------------------------------------------------------------ layered scanner

    private LayeredFileScanner layered(boolean required) {
        AntivirusProperties props = new AntivirusProperties("clamav", required,
                new AntivirusProperties.Clamav("127.0.0.1", clamd.port(), Duration.ofMillis(500), Duration.ofSeconds(2), 1));
        return new LayeredFileScanner(new DefaultFileScanner(), new Antivirus(props, new HowlersMetrics(new SimpleMeterRegistry())));
    }

    private static FileScanner.Input text(String body) {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return new FileScanner.Input("notes.txt", b, b.length, null, false, () -> new ByteArrayInputStream(b));
    }

    @Test
    void structuralChecksRunFirstThenClamav() {
        FileScanner.Result clean = layered(false).scan(text("plain notes"));
        assertThat(clean.status()).isEqualTo(FileScanner.Status.CLEAN);
        assertThat(clean.engine()).isEqualTo(FileScanner.Engine.CLAMAV);
        assertThat(clean.reason()).startsWith("Scanned by ClamAV");

        FileScanner.Result infected = layered(false).scan(text("notes " + FakeClamd.MARKER));
        assertThat(infected.status()).isEqualTo(FileScanner.Status.REJECTED);
        assertThat(infected.reason()).contains("Eicar-Signature");

        // A renamed executable never reaches clamd.
        int before = clamd.payloads().size();
        byte[] exe = {'M', 'Z', 0, 0, 1, 2, 3};
        FileScanner.Result blocked = layered(false).scan(new FileScanner.Input("photo.txt", exe, exe.length, null, false,
                () -> new ByteArrayInputStream(exe)));
        assertThat(blocked.status()).isEqualTo(FileScanner.Status.REJECTED);
        assertThat(clamd.payloads()).hasSize(before);
    }

    @Test
    void unreachableClamdFailsOpenOrClosedPerConfig() {
        clamd.mode(FakeClamd.Mode.CLOSE_IMMEDIATELY);
        FileScanner.Result open = layered(false).scan(text("plain notes"));
        assertThat(open.status()).isEqualTo(FileScanner.Status.CLEAN);
        assertThat(open.engine()).isEqualTo(FileScanner.Engine.STRUCTURAL);
        assertThat(open.reason()).contains("Not scanned for malware");

        FileScanner.Result closed = layered(true).scan(text("plain notes"));
        assertThat(closed.status()).isEqualTo(FileScanner.Status.REJECTED);
        assertThat(closed.reason()).contains("isn't reachable");
    }

    @Test
    void disabledAntivirusLeavesStructuralResult() {
        AntivirusProperties off = new AntivirusProperties("none", false, null);
        LayeredFileScanner s = new LayeredFileScanner(new DefaultFileScanner(), new Antivirus(off, new HowlersMetrics(new SimpleMeterRegistry())));
        FileScanner.Result r = s.scan(text("notes " + FakeClamd.MARKER));
        assertThat(r.status()).isEqualTo(FileScanner.Status.CLEAN);
        assertThat(r.engine()).isEqualTo(FileScanner.Engine.STRUCTURAL);
        assertThat(clamd.payloads()).isEmpty();
    }
}
