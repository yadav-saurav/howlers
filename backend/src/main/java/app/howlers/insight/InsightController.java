package app.howlers.insight;

import app.howlers.common.security.AuthUser;
import app.howlers.insight.InsightDtos.ContextView;
import app.howlers.insight.InsightDtos.DigestView;
import app.howlers.insight.InsightDtos.StanceRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "Context Mode & Digest")
@RestController
@RequestMapping("/api/v1")
public class InsightController {
    private final ContextService context;
    private final DigestService digests;

    public InsightController(ContextService context, DigestService digests) {
        this.context = context;
        this.digests = digests;
    }

    @GetMapping("/posts/{id}/context")
    public ContextView context(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return context.build(id, user);
    }

    @PutMapping("/comments/{id}/stance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void label(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody StanceRequest req) {
        context.label(id, user, req.stance());
    }

    @DeleteMapping("/comments/{id}/stance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlabel(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        context.unlabel(id, user);
    }

    @GetMapping("/posts/{id}/digest")
    public DigestView digest(@PathVariable long id, @RequestParam(defaultValue = "standard") String length,
                             @RequestParam(defaultValue = "balanced") String focus, @AuthenticationPrincipal AuthUser user) {
        return digests.get(id, length, focus, user);
    }

    @PostMapping("/posts/{id}/digest")
    public DigestView refresh(@PathVariable long id, @RequestParam(defaultValue = "standard") String length,
                              @RequestParam(defaultValue = "balanced") String focus, @AuthenticationPrincipal AuthUser user) {
        return digests.refresh(id, length, focus, user);
    }
}
