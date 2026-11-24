package app.howlers.reaction;

import app.howlers.common.error.ApiException;
import app.howlers.common.security.AuthUser;
import app.howlers.reaction.ReactionService.TargetReactions;
import app.howlers.reaction.ReactionService.ToggleResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Emoji reactions (D-044). Reads are public for posts and comments (same rules as the content); writes need sign-in. */
@Tag(name = "Reactions")
@RestController
@RequestMapping("/api/v1/reactions")
public class ReactionController {
    private final ReactionService reactions;

    public ReactionController(ReactionService reactions) {
        this.reactions = reactions;
    }

    public record SetRequest(@NotBlank String targetType, @NotNull Long targetId, @NotBlank String reaction, Boolean on) {}

    @GetMapping("/catalog")
    public List<ReactionCatalog.Kind> catalog() {
        return ReactionCatalog.ALL;
    }

    /** {@code GET /reactions?targetType=COMMENT&ids=1,2,3}: one entry per visible id, in request order. */
    @GetMapping
    public List<TargetReactions> list(@AuthenticationPrincipal AuthUser user, @RequestParam String targetType,
                                      @RequestParam String ids) {
        List<Long> parsed;
        try {
            parsed = Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Long::parseLong).toList();
        } catch (NumberFormatException e) {
            throw ApiException.field("ids", "Use a comma-separated list of ids");
        }
        return reactions.summaries(ReactionService.parseType(targetType), parsed, user);
    }

    /** Adds ({@code on: true}), removes ({@code on: false}) or toggles (no {@code on}) one reaction. */
    @PostMapping
    public ToggleResult set(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SetRequest req) {
        return reactions.set(user, ReactionService.parseType(req.targetType()), req.targetId(), req.reaction(), req.on());
    }
}
