package app.howlers.files;

import app.howlers.antivirus.Antivirus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Structural checks first, then ClamAV when it's configured (D-029). The structural pass is cheap and catches renamed
 * executables, scripts and archive bombs without a network round trip; only files it accepts are streamed to clamd.
 * End-to-end encrypted files stay UNSCANNED (the server only has ciphertext).
 */
@Primary
@Component
public class LayeredFileScanner implements FileScanner {
    private final DefaultFileScanner structural;
    private final Antivirus antivirus;

    public LayeredFileScanner(DefaultFileScanner structural, Antivirus antivirus) {
        this.structural = structural;
        this.antivirus = antivirus;
    }

    @Override
    public Result scan(Input in) {
        Result s = structural.scan(in);
        if (s.status() != Status.CLEAN || in.e2ee() || !antivirus.enabled() || in.content() == null) return s;
        Antivirus.Verdict v = antivirus.scan(in.content());
        return switch (v.outcome()) {
            case CLEAN -> new Result(Status.CLEAN, s.contentType(), "Scanned by ClamAV: no known threats found. " + s.reason(), Engine.CLAMAV);
            case INFECTED -> Result.rejected("ClamAV flagged this file (" + v.signature() + "), so it wasn't uploaded.");
            case UNAVAILABLE -> {
                if (antivirus.required()) {
                    yield Result.rejected("The antivirus scanner isn't reachable right now, so uploads are paused. Try again in a few minutes.");
                }
                yield new Result(Status.CLEAN, s.contentType(),
                        s.reason() + " Not scanned for malware: the antivirus scanner was unavailable.", Engine.STRUCTURAL);
            }
        };
    }
}
