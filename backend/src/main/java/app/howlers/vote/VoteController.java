package app.howlers.vote;

import app.howlers.common.security.AuthUser;
import app.howlers.post.dto.PostDtos.VoteRequest;
import app.howlers.post.dto.PostDtos.VoteResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Votes")
@RestController
@RequestMapping("/api/v1")
public class VoteController {
    private final VoteService votes;

    public VoteController(VoteService votes) {
        this.votes = votes;
    }

    @PostMapping("/posts/{id}/vote")
    public VoteResult votePost(@PathVariable long id, @AuthenticationPrincipal AuthUser user,
                               @Valid @RequestBody VoteRequest req) {
        return votes.votePost(id, user, req.value());
    }

    @PostMapping("/comments/{id}/vote")
    public VoteResult voteComment(@PathVariable long id, @AuthenticationPrincipal AuthUser user,
                                  @Valid @RequestBody VoteRequest req) {
        return votes.voteComment(id, user, req.value());
    }
}
