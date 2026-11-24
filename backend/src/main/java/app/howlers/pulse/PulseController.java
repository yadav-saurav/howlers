package app.howlers.pulse;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.Cursors;
import app.howlers.pulse.PulseDtos.CreatePulse;
import app.howlers.pulse.PulseDtos.PulseDto;
import app.howlers.pulse.PulseDtos.PulseReason;
import app.howlers.pulse.PulseDtos.ResolveRequest;
import app.howlers.pulse.PulseDtos.VoteRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community Pulse")
@RestController
@RequestMapping("/api/v1")
public class PulseController {
    private final PulseService pulses;

    public PulseController(PulseService pulses) {
        this.pulses = pulses;
    }

    @GetMapping("/communities/{slug}/pulses")
    public Object list(@PathVariable String slug, @RequestParam(defaultValue = "active") String status,
                       @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                       @AuthenticationPrincipal AuthUser user) {
        if ("closed".equalsIgnoreCase(status)) return pulses.history(slug, user, cursor, Cursors.clampLimit(limit, 10, 30));
        return pulses.active(slug, user);
    }

    @GetMapping("/pulses/active")
    public List<PulseDto> activeForMe(@RequestParam(defaultValue = "5") int limit, @AuthenticationPrincipal AuthUser user) {
        return pulses.activeForViewer(user, limit);
    }

    @PostMapping("/communities/{slug}/pulses")
    @ResponseStatus(HttpStatus.CREATED)
    public PulseDto create(@PathVariable String slug, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreatePulse req) {
        return pulses.create(slug, user, req);
    }

    @GetMapping("/pulses/{id}")
    public PulseDto get(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return pulses.get(id, user);
    }

    @PostMapping("/pulses/{id}/vote")
    public PulseDto vote(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody VoteRequest req) {
        return pulses.vote(id, user, req.optionIds(), req.reasoning());
    }

    @PostMapping("/pulses/{id}/close")
    public PulseDto close(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return pulses.close(id, user);
    }

    @PostMapping("/pulses/{id}/resolve")
    public PulseDto resolve(@PathVariable long id, @AuthenticationPrincipal AuthUser user, @Valid @RequestBody ResolveRequest req) {
        return pulses.resolve(id, user, req.optionId());
    }

    @GetMapping("/pulses/{id}/reasons")
    public List<PulseReason> reasons(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return pulses.reasons(id, user);
    }

    @DeleteMapping("/pulses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        pulses.delete(id, user);
    }
}
