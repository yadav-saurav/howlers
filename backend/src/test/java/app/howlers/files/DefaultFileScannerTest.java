package app.howlers.files;

import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.files.FileScanner.Input;
import app.howlers.files.FileScanner.Status;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DefaultFileScannerTest {
    private final DefaultFileScanner scanner = new DefaultFileScanner();

    private FileScanner.Result scan(String name, byte[] head) {
        return scanner.scan(new Input(name, head, head.length, null, false));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void identifiesByContentAndChecksTheExtension() {
        assertThat(scan("a.pdf", ascii("%PDF-1.4 ...")).contentType()).isEqualTo("application/pdf");
        assertThat(scan("noext", ascii("%PDF-1.4 ...")).status()).isEqualTo(Status.CLEAN);
        assertThat(scan("a.jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}).contentType()).isEqualTo("image/jpeg");
        assertThat(scan("song.mp3", ascii("ID3\u0004...")).contentType()).isEqualTo("audio/mpeg");
        assertThat(scan("clip.mov", ascii("\0\0\0\u0014ftypqt  ")).contentType()).isEqualTo("video/quicktime");
        assertThat(scan("a.pdf", ascii("GIF89a....")).status()).isEqualTo(Status.REJECTED);
        assertThat(scan("data.csv", ascii("a,b\n1,2\n")).contentType()).isEqualTo("text/csv");
        assertThat(scan("notes.md", "# Café ☕".getBytes(StandardCharsets.UTF_8)).contentType()).isEqualTo("text/markdown");
        assertThat(scan("weird.xyz", ascii("plain words")).status()).isEqualTo(Status.REJECTED);
        assertThat(scan("blob.dat", new byte[]{1, 2, 0, 4}).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void blocksExecutablesAndScriptsWhateverTheyAreCalled() {
        assertThat(scan("photo.jpg", ascii("MZ\u0090\0")).reason()).contains("Windows executable");
        assertThat(scan("x", new byte[]{0x7F, 'E', 'L', 'F'}).reason()).contains("Linux executable");
        assertThat(scan("notes.txt", ascii("#!/bin/bash\necho hi")).reason()).contains("script");
        assertThat(scan("tool.ps1", ascii("Write-Host hi")).status()).isEqualTo(Status.REJECTED);
        assertThat(scan("report.xlsm", ascii("PK\u0003\u0004")).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void encryptedUploadsAreUnscannedButMustBeContainers() {
        byte[] container = new byte[32];
        System.arraycopy(DefaultFileScanner.E2EE_MAGIC, 0, container, 0, DefaultFileScanner.E2EE_MAGIC.length);
        var ok = scanner.scan(new Input("x.exe", container, 32, null, true));
        assertThat(ok.status()).isEqualTo(Status.UNSCANNED);
        assertThat(scanner.scan(new Input("x", ascii("%PDF-1.4"), 8, null, true)).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void downloadGrantsAreSignedAndExpire() {
        Clock now = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        DownloadGrants g = new DownloadGrants("secret", now);
        String grant = g.issue(DownloadGrants.Kind.L, 42, 7L, now.instant().plusSeconds(60));
        assertThat(g.verify(grant)).hasValueSatisfying(v -> {
            assertThat(v.targetId()).isEqualTo(42);
            assertThat(v.viewerId()).isEqualTo(7L);
        });
        assertThat(new DownloadGrants("other-secret", now).verify(grant)).isEmpty();
        assertThat(g.verify(grant.replace(grant.charAt(2), grant.charAt(2) == 'A' ? 'B' : 'A'))).isEmpty();
        Clock later = Clock.fixed(Instant.parse("2026-01-01T00:02:00Z"), ZoneOffset.UTC);
        assertThat(new DownloadGrants("secret", later).verify(grant)).isEmpty();
    }
}
