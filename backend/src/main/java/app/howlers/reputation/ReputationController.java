package app.howlers.reputation;

import app.howlers.common.security.AuthUser;
import app.howlers.reputation.ReputationDtos.Policy;
import app.howlers.reputation.ReputationDtos.PostExpertise;
import app.howlers.reputation.ReputationDtos.ReputationOverview;
import app.howlers.reputation.ReputationDtos.TopicLeaderboard;
import app.howlers.reputation.ReputationDtos.UserReputation;
import app.howlers.reputation.ReputationService.RunResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reputation")
@RestController
@RequestMapping("/api/v1")
public class ReputationController {
    private final ReputationService reputation;

    public ReputationController(ReputationService reputation) {
        this.reputation = reputation;
    }

    @GetMapping("/topics/{slug}/leaders")
    public TopicLeaderboard leaders(@PathVariable String slug, @RequestParam(defaultValue = "25") int limit) {
        return reputation.leaders(slug, limit);
    }

    @GetMapping("/reputation/topics")
    public ReputationOverview overview() {
        return reputation.overview();
    }

    @GetMapping("/reputation/policy")
    public Policy policy() {
        return Policy.current();
    }

    @GetMapping("/users/{username}/reputation")
    public UserReputation forUser(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        return reputation.forUser(username, user);
    }

    @GetMapping("/posts/{id}/expertise")
    public PostExpertise forPost(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return reputation.forPost(id, user);
    }

    /** Platform admins can force a full rebuild (it also runs nightly and at startup). */
    @PostMapping("/admin/reputation/rebuild")
    public RunResult rebuild() {
        return reputation.rebuildAll();
    }
}
