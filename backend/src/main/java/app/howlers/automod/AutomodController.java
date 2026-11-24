package app.howlers.automod;

import app.howlers.automod.AutomodService.HitDto;
import app.howlers.automod.AutomodService.PreviewResult;
import app.howlers.automod.AutomodService.RuleDto;
import app.howlers.automod.AutomodService.RuleRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.Cursors;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** Community automod rules (D-045). Moderators of the community and platform admins only (checked in the service). */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/communities/{slug}/mod/automod")
public class AutomodController {
    private final AutomodService automod;

    public AutomodController(AutomodService automod) {
        this.automod = automod;
    }

    @GetMapping("/rules")
    public List<RuleDto> rules(@AuthenticationPrincipal AuthUser user, @PathVariable String slug) {
        return automod.rules(slug, user);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto create(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @RequestBody RuleRequest req) {
        return automod.create(slug, user, req);
    }

    /** Partial update: fields left out keep their values. */
    @PutMapping("/rules/{id}")
    public RuleDto update(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @PathVariable long id,
                          @RequestBody RuleRequest req) {
        return automod.update(slug, user, id, req);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @PathVariable long id) {
        automod.delete(slug, user, id);
    }

    /** Tests a draft rule against recent content without saving it or changing anything. */
    @PostMapping("/preview")
    public PreviewResult preview(@AuthenticationPrincipal AuthUser user, @PathVariable String slug, @RequestBody RuleRequest req) {
        return automod.preview(slug, user, req);
    }

    @GetMapping("/hits")
    public List<HitDto> hits(@AuthenticationPrincipal AuthUser user, @PathVariable String slug,
                             @RequestParam(required = false) Integer limit) {
        return automod.hits(slug, user, Cursors.clampLimit(limit, 50, 200));
    }
}
