package app.howlers.push;

import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.push.PushService.Device;
import app.howlers.push.PushService.PushConfig;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Web push: public configuration and the signed-in user's device subscriptions (D-040). */
@Tag(name = "Push")
@RestController
public class PushController {
    private final PushService push;
    private final RateLimiter limiter;

    public PushController(PushService push, RateLimiter limiter) {
        this.push = push;
        this.limiter = limiter;
    }

    public record Keys(@NotBlank @Size(max = 100) String p256dh, @NotBlank @Size(max = 40) String auth) {}

    public record SubscribeRequest(@NotBlank @Size(max = 1024) String endpoint, @NotNull @Valid Keys keys,
                                   @Size(max = 80) String label, @Size(max = 10) List<String> categories) {}

    public record UpdateRequest(@Size(max = 10) List<String> categories, @Size(max = 80) String label) {}

    public record UnsubscribeRequest(@NotBlank @Size(max = 1024) String endpoint) {}

    public record TestResult(String outcome, int status, String message) {}

    /** Whether push is available on this server, and the VAPID public key browsers subscribe with. */
    @GetMapping("/api/v1/push/config")
    public PushConfig config() {
        return push.config();
    }

    @GetMapping("/api/v1/me/push/subscriptions")
    public List<Device> devices(@AuthenticationPrincipal AuthUser user) {
        return push.devices(user.id());
    }

    @PostMapping("/api/v1/me/push/subscriptions")
    public Device subscribe(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SubscribeRequest req) {
        limiter.check("push-sub:" + user.id(), 20, Duration.ofMinutes(10));
        return push.subscribe(user.id(), req.endpoint(), req.keys().p256dh(), req.keys().auth(), req.label(), req.categories());
    }

    @PatchMapping("/api/v1/me/push/subscriptions/{id}")
    public Device update(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody UpdateRequest req) {
        return push.updateCategories(user.id(), id, req.categories(), req.label());
    }

    @DeleteMapping("/api/v1/me/push/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        push.unsubscribe(user.id(), id);
    }

    /** Removes this browser's subscription by endpoint (used when signing out). */
    @PostMapping("/api/v1/me/push/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UnsubscribeRequest req) {
        push.unsubscribeEndpoint(user.id(), req.endpoint());
    }

    @PostMapping("/api/v1/me/push/subscriptions/{id}/test")
    public TestResult test(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        limiter.check("push-test:" + user.id(), 5, Duration.ofMinutes(1));
        PushService.Result r = push.sendTest(user.id(), id);
        String message = switch (r.outcome()) {
            case DELIVERED -> "The push service accepted the test notification. It should appear on that device shortly.";
            case GONE -> "That device's subscription has expired, so it was removed. Turn push on again there.";
            case REJECTED -> "The push service refused the notification (HTTP " + r.status() + "). Try turning push off and on.";
            case FAILED -> "The push service couldn't be reached. Try again in a moment.";
            case UNAVAILABLE -> "Push notifications aren't set up on this server.";
        };
        return new TestResult(r.outcome().name(), r.status(), message);
    }
}
