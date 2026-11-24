package app.howlers.recognition;

import app.howlers.common.security.AuthUser;
import app.howlers.recognition.RecognitionDtos.EndorseRequest;
import app.howlers.recognition.RecognitionDtos.Formula;
import app.howlers.recognition.RecognitionDtos.Leaderboard;
import app.howlers.recognition.RecognitionDtos.ModerationRecord;
import app.howlers.recognition.RecognitionDtos.ModeratorSettings;
import app.howlers.recognition.RecognitionDtos.Peers;
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

/**
 * Moderator recognition. Leaderboards and records are public GETs (opted-out moderators and private communities are
 * filtered in the service); the peers list checks for a principal itself because GETs under /api/v1 are public.
 */
@Tag(name = "Moderator recognition")
@RestController
@RequestMapping("/api/v1")
public class RecognitionController {
    private final RecognitionService recognition;

    public RecognitionController(RecognitionService recognition) {
        this.recognition = recognition;
    }

    @GetMapping("/moderators/leaderboard")
    public Leaderboard leaderboard(@RequestParam(required = false) String period, @RequestParam(required = false) String community,
                                   @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal AuthUser user) {
        return recognition.leaderboard(period, community, limit, user);
    }

    @GetMapping("/moderators/formula")
    public Formula formula() {
        return Formula.current();
    }

    @GetMapping("/users/{username}/moderation-record")
    public ModerationRecord record(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        return recognition.record(username, user);
    }

    @GetMapping("/communities/{slug}/moderators/peers")
    public Peers peers(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return recognition.peers(slug, user);
    }

    @PostMapping("/communities/{slug}/moderators/{username}/endorsement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endorse(@PathVariable String slug, @PathVariable String username, @AuthenticationPrincipal AuthUser user,
                        @Valid @RequestBody(required = false) EndorseRequest req) {
        recognition.endorse(slug, user, username, req == null ? null : req.note());
    }

    @DeleteMapping("/communities/{slug}/moderators/{username}/endorsement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable String slug, @PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        recognition.withdraw(slug, user, username);
    }

    @GetMapping("/me/moderator-settings")
    public ModeratorSettings settings(@AuthenticationPrincipal AuthUser user) {
        return recognition.settings(user);
    }

    @PutMapping("/me/moderator-settings")
    public ModeratorSettings updateSettings(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ModeratorSettings req) {
        return recognition.updateSettings(user, req.publicRanking());
    }
}
