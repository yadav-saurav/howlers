package app.howlers.ai;

import app.howlers.ai.AiDtos.AskRequest;
import app.howlers.ai.AiDtos.AskResponse;
import app.howlers.ai.AiDtos.ConversationDto;
import app.howlers.ai.AiDtos.ConversationSummary;
import app.howlers.ai.AiDtos.FeedbackRequest;
import app.howlers.ai.AiDtos.Status;
import app.howlers.common.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Community AI. Everything except status requires sign-in (see SecurityConfig: /api/v1/me/** and non-GET). */
@Tag(name = "Community AI")
@RestController
@RequestMapping("/api/v1")
public class AiController {
    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @GetMapping("/ai/status")
    public Status status() {
        return ai.status();
    }

    @PostMapping("/me/ai/ask")
    public AskResponse ask(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody AskRequest req) {
        return ai.ask(user, req);
    }

    @GetMapping("/me/ai/conversations")
    public List<ConversationSummary> list(@AuthenticationPrincipal AuthUser user) {
        return ai.list(user);
    }

    @GetMapping("/me/ai/conversations/{id}")
    public ConversationDto get(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return ai.get(user, id);
    }

    @DeleteMapping("/me/ai/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        ai.delete(user, id);
    }

    @PostMapping("/me/ai/messages/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody FeedbackRequest req) {
        ai.feedback(user, id, req.value());
    }
}
