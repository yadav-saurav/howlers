package app.howlers.spam;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.common.security.Viewer;
import app.howlers.spam.SpamService.Resolution;
import app.howlers.spam.SpamService.SpamFlagDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Community-level review of held content. GETs under /communities are public by default, so each call requires a user. */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1")
public class SpamController {
    private final SpamService spam;

    public SpamController(SpamService spam) {
        this.spam = spam;
    }

    @GetMapping("/communities/{slug}/mod/held")
    public List<SpamFlagDto> held(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return spam.communityHolds(slug, Viewer.require(user));
    }

    @PostMapping("/mod/flags/{id}/{verdict}")
    public Resolution resolve(@PathVariable long id, @PathVariable String verdict, @AuthenticationPrincipal AuthUser user) {
        boolean approve = switch (verdict) {
            case "approve" -> true;
            case "remove" -> false;
            default -> throw ApiException.notFound("Action");
        };
        return spam.resolve(id, Viewer.require(user), approve);
    }
}
