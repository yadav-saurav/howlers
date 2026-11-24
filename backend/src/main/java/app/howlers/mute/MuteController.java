package app.howlers.mute;

import app.howlers.common.security.AuthUser;
import app.howlers.common.security.Viewer;
import app.howlers.mute.MuteService.Mutes;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mutes")
@RestController
@RequestMapping("/api/v1")
public class MuteController {
    private final MuteService mutes;

    public MuteController(MuteService mutes) {
        this.mutes = mutes;
    }

    @GetMapping("/me/mutes")
    public Mutes list(@AuthenticationPrincipal AuthUser user) {
        return mutes.list(Viewer.require(user));
    }

    @PutMapping("/users/{username}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void muteUser(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        mutes.muteUser(Viewer.require(user), username, true);
    }

    @DeleteMapping("/users/{username}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmuteUser(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        mutes.muteUser(Viewer.require(user), username, false);
    }

    @PutMapping("/communities/{slug}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void muteCommunity(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        mutes.muteCommunity(Viewer.require(user), slug, true);
    }

    @DeleteMapping("/communities/{slug}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmuteCommunity(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        mutes.muteCommunity(Viewer.require(user), slug, false);
    }
}
