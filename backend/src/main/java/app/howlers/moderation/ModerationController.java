package app.howlers.moderation;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationDtos.Appeal;
import app.howlers.moderation.ModerationDtos.AppealRequest;
import app.howlers.moderation.ModerationDtos.Ban;
import app.howlers.moderation.ModerationDtos.BanRequest;
import app.howlers.moderation.ModerationDtos.ModAction;
import app.howlers.moderation.ModerationDtos.ModStats;
import app.howlers.moderation.ModerationDtos.MyModAction;
import app.howlers.moderation.ModerationDtos.QueueItem;
import app.howlers.moderation.ModerationDtos.ReasonRequest;
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.moderation.ModerationDtos.ResolveAppealRequest;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.PostDetail;
import app.howlers.user.dto.UserDtos.UserSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moderation endpoints. The community-scoped GETs live under /communities/{slug}/mod, which the security config
 * would otherwise leave public for GET: every method here therefore requires a principal and checks moderator
 * rights in the service.
 */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1")
public class ModerationController {
    private final ModerationService mod;
    private final PostService posts;

    public ModerationController(ModerationService mod, PostService posts) {
        this.mod = mod;
        this.posts = posts;
    }

    private static AuthUser require(AuthUser user) {
        if (user == null) throw ApiException.unauthorized("Authentication required");
        return user;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ReportRequest req) {
        mod.report(require(user), req);
    }

    @GetMapping("/communities/{slug}/mod/queue")
    public List<QueueItem> queue(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return mod.queue(slug, require(user));
    }

    @GetMapping("/communities/{slug}/mod/stats")
    public ModStats stats(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return mod.stats(slug, require(user));
    }

    @GetMapping("/communities/{slug}/mod/log")
    public CursorPage<ModAction> log(@PathVariable String slug, @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit, @AuthenticationPrincipal AuthUser user) {
        return mod.log(slug, require(user), cursor, Cursors.clampLimit(limit, 30, 100));
    }

    @GetMapping("/communities/{slug}/mod/bans")
    public List<Ban> bans(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return mod.bans(slug, require(user));
    }

    @PostMapping("/communities/{slug}/mod/bans")
    @ResponseStatus(HttpStatus.CREATED)
    public Ban ban(@PathVariable String slug, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody BanRequest req) {
        return mod.ban(slug, require(user), req.username(), req.reason(), req.days());
    }

    @DeleteMapping("/communities/{slug}/mod/bans/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unban(@PathVariable String slug, @PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        mod.unban(slug, require(user), username);
    }

    @GetMapping("/communities/{slug}/mod/appeals")
    public List<Appeal> appeals(@PathVariable String slug, @RequestParam(defaultValue = "PENDING") String status,
                                @AuthenticationPrincipal AuthUser user) {
        return mod.appeals(slug, require(user), !"ALL".equalsIgnoreCase(status));
    }

    @PostMapping("/mod/appeals/{id}/resolve")
    public Appeal resolve(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody ResolveAppealRequest req) {
        return mod.resolveAppeal(id, require(user), req.accept(), req.response());
    }

    @PostMapping("/mod/posts/{id}/{action}")
    public PostDetail postAction(@PathVariable long id, @PathVariable String action, @AuthenticationPrincipal AuthUser user,
                                 @Valid @RequestBody(required = false) ReasonRequest req) {
        Action a = switch (action) {
            case "remove" -> Action.REMOVE_POST;
            case "approve" -> Action.APPROVE_POST;
            case "lock" -> Action.LOCK;
            case "unlock" -> Action.UNLOCK;
            case "pin" -> Action.PIN;
            case "unpin" -> Action.UNPIN;
            default -> throw ApiException.notFound("Action");
        };
        mod.postAction(id, require(user), a, req == null ? null : req.reason());
        return posts.detail(id, user);
    }

    @PostMapping("/mod/comments/{id}/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void commentAction(@PathVariable long id, @PathVariable String action, @AuthenticationPrincipal AuthUser user,
                              @Valid @RequestBody(required = false) ReasonRequest req) {
        Action a = switch (action) {
            case "remove" -> Action.REMOVE_COMMENT;
            case "approve" -> Action.APPROVE_COMMENT;
            default -> throw ApiException.notFound("Action");
        };
        mod.commentAction(id, require(user), a, req == null ? null : req.reason());
    }

    @GetMapping("/me/mod-actions/{id}")
    public MyModAction mine(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return mod.myAction(id, user);
    }

    @PostMapping("/me/mod-actions/{id}/appeal")
    @ResponseStatus(HttpStatus.CREATED)
    public Appeal appeal(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody AppealRequest req) {
        return mod.submitAppeal(id, user, req.message());
    }

    @GetMapping("/me/blocks")
    public List<UserSummary> blocks(@AuthenticationPrincipal AuthUser user) {
        return mod.blocks(user);
    }

    @PutMapping("/users/{username}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        mod.block(user, username, true);
    }

    @DeleteMapping("/users/{username}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        mod.block(user, username, false);
    }
}
