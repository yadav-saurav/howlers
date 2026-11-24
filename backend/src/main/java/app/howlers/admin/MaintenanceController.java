package app.howlers.admin;

import app.howlers.antivirus.Antivirus;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.messaging.LegacyDmMedia;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privacy and safety maintenance (D-029, D-030). ADMIN only (SecurityConfig, re-checked here). */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/maintenance")
public class MaintenanceController {
    private final LegacyDmMedia legacy;
    private final Antivirus antivirus;

    public MaintenanceController(LegacyDmMedia legacy, Antivirus antivirus) {
        this.legacy = legacy;
        this.antivirus = antivirus;
    }

    /** engine: "none" or "clamav"; version is clamd's VERSION reply, or null when it isn't reachable. */
    public record AntivirusStatus(String engine, boolean required, boolean reachable, String version) {}

    public record LegacyDmMediaStatus(long pending, List<LegacyDmMedia.Run> runs) {}

    public record Status(AntivirusStatus antivirus, LegacyDmMediaStatus legacyDmMedia) {}

    @GetMapping
    public Status status(@AuthenticationPrincipal AuthUser user) {
        requireAdmin(user);
        String version = antivirus.version();
        return new Status(new AntivirusStatus(antivirus.enabled() ? "clamav" : "none", antivirus.required(), version != null, version),
                new LegacyDmMediaStatus(legacy.pendingCount(), legacy.runs(10)));
    }

    /** Runs the legacy DM image move now (it also runs at startup). Idempotent; returns the recorded run. */
    @PostMapping("/legacy-dm-media")
    public LegacyDmMedia.Run migrateLegacyDmMedia(@AuthenticationPrincipal AuthUser user) {
        requireAdmin(user);
        return legacy.run(user.id());
    }

    private static void requireAdmin(AuthUser user) {
        if (user == null || !user.isAdmin()) throw ApiException.forbidden("Admins only");
    }
}
