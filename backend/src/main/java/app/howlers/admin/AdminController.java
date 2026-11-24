package app.howlers.admin;

import app.howlers.admin.AdminDtos.AdminCommunity;
import app.howlers.admin.AdminDtos.AdminReport;
import app.howlers.admin.AdminDtos.AdminUser;
import app.howlers.admin.AdminDtos.AdminUserDetail;
import app.howlers.admin.AdminDtos.AuditEntry;
import app.howlers.admin.AdminDtos.CommunityStatusRequest;
import app.howlers.admin.AdminDtos.OptionalReasonRequest;
import app.howlers.admin.AdminDtos.Overview;
import app.howlers.admin.AdminDtos.ReasonRequest;
import app.howlers.admin.AdminDtos.RoleRequest;
import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.spam.SpamService.Resolution;
import app.howlers.spam.SpamService.SpamFlagDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin API. SecurityConfig already limits /api/v1/admin/** to the ADMIN role; the service checks again. */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/overview")
    public Overview overview(@AuthenticationPrincipal AuthUser user) {
        return admin.overview(user);
    }

    @GetMapping("/users")
    public CursorPage<AdminUser> users(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                       @AuthenticationPrincipal AuthUser user) {
        return admin.users(user, q, status, cursor, Cursors.clampLimit(limit, 25, 100));
    }

    @GetMapping("/users/{id}")
    public AdminUserDetail user(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return admin.user(user, id);
    }

    @PostMapping("/users/{id}/suspend")
    public AdminUserDetail suspend(@PathVariable long id, @Valid @RequestBody ReasonRequest req, @AuthenticationPrincipal AuthUser user) {
        return admin.suspend(user, id, req.reason());
    }

    @PostMapping("/users/{id}/unsuspend")
    public AdminUserDetail unsuspend(@PathVariable long id, @Valid @RequestBody(required = false) OptionalReasonRequest req,
                                     @AuthenticationPrincipal AuthUser user) {
        return admin.unsuspend(user, id, req == null ? null : req.reason());
    }

    @PostMapping("/users/{id}/role")
    public AdminUserDetail role(@PathVariable long id, @Valid @RequestBody RoleRequest req, @AuthenticationPrincipal AuthUser user) {
        return admin.setRole(user, id, req.role(), req.reason());
    }

    @GetMapping("/communities")
    public CursorPage<AdminCommunity> communities(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                                  @AuthenticationPrincipal AuthUser user) {
        return admin.communities(user, q, status, cursor, Cursors.clampLimit(limit, 25, 100));
    }

    @PostMapping("/communities/{slug}/status")
    public AdminCommunity communityStatus(@PathVariable String slug, @Valid @RequestBody CommunityStatusRequest req,
                                          @AuthenticationPrincipal AuthUser user) {
        return admin.setCommunityStatus(user, slug, req.status(), req.reason());
    }

    @GetMapping("/flags")
    public CursorPage<SpamFlagDto> flags(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit, @AuthenticationPrincipal AuthUser user) {
        return admin.flags(user, status, cursor, Cursors.clampLimit(limit, 25, 100));
    }

    @PostMapping("/flags/{id}/{verdict}")
    public Resolution resolveFlag(@PathVariable long id, @PathVariable String verdict, @AuthenticationPrincipal AuthUser user) {
        boolean approve = switch (verdict) {
            case "approve" -> true;
            case "remove" -> false;
            default -> throw ApiException.notFound("Action");
        };
        return admin.resolveFlag(user, id, approve);
    }

    @GetMapping("/reports")
    public List<AdminReport> reports(@AuthenticationPrincipal AuthUser user) {
        return admin.reports(user);
    }

    @GetMapping("/audit")
    public CursorPage<AuditEntry> audit(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                        @AuthenticationPrincipal AuthUser user) {
        return admin.audit(user, cursor, Cursors.clampLimit(limit, 30, 100));
    }
}
