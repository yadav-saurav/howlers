package app.howlers.community;

import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.OffsetPage;
import app.howlers.community.dto.CommunityDtos.CommunityDetail;
import app.howlers.community.dto.CommunityDtos.CommunitySummary;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.community.dto.CommunityDtos.FlairInput;
import app.howlers.community.dto.CommunityDtos.MemberDto;
import app.howlers.community.dto.CommunityDtos.RuleInput;
import app.howlers.community.dto.CommunityDtos.SetRoleRequest;
import app.howlers.community.dto.CommunityDtos.UpdateCommunityRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Communities")
@RestController
@Validated
@RequestMapping("/api/v1")
public class CommunityController {
    private final CommunityService service;
    private final RateLimiter limiter;

    public CommunityController(CommunityService service, RateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping("/communities")
    public OffsetPage<CommunitySummary> directory(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String topic,
                                                  @RequestParam(defaultValue = "popular") String sort,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @AuthenticationPrincipal AuthUser user) {
        return service.directory(q, topic, sort, page, size, user);
    }

    @GetMapping("/communities/trending")
    public List<CommunitySummary> trending(@RequestParam(defaultValue = "5") int limit, @AuthenticationPrincipal AuthUser user) {
        return service.trending(limit, user);
    }

    @GetMapping("/me/communities")
    public List<CommunitySummary> mine(@AuthenticationPrincipal AuthUser user) {
        return service.mine(user);
    }

    @PostMapping("/communities")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityDetail create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateCommunityRequest req) {
        limiter.check("community:create:" + user.id(), 5, Duration.ofDays(1));
        return service.detail(service.create(user, req), user);
    }

    @GetMapping("/communities/{slug}")
    public CommunityDetail detail(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        return service.detail(slug, user);
    }

    @PatchMapping("/communities/{slug}")
    public CommunityDetail update(@PathVariable String slug, @AuthenticationPrincipal AuthUser user,
                                  @Valid @RequestBody UpdateCommunityRequest req) {
        return service.update(slug, user, req);
    }

    @PutMapping("/communities/{slug}/rules")
    public CommunityDetail rules(@PathVariable String slug, @AuthenticationPrincipal AuthUser user,
                                 @RequestBody List<@Valid RuleInput> rules) {
        return service.replaceRules(slug, user, rules);
    }

    @PutMapping("/communities/{slug}/flairs")
    public CommunityDetail flairs(@PathVariable String slug, @AuthenticationPrincipal AuthUser user,
                                  @RequestBody List<@Valid FlairInput> flairs) {
        return service.replaceFlairs(slug, user, flairs);
    }

    @PostMapping(value = "/communities/{slug}/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommunityDetail icon(@PathVariable String slug, @AuthenticationPrincipal AuthUser user,
                                @RequestParam("file") MultipartFile file) {
        return service.uploadImage(slug, user, file, false);
    }

    @PostMapping(value = "/communities/{slug}/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommunityDetail banner(@PathVariable String slug, @AuthenticationPrincipal AuthUser user,
                                  @RequestParam("file") MultipartFile file) {
        return service.uploadImage(slug, user, file, true);
    }

    @PostMapping("/communities/{slug}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        limiter.check("join:" + user.id(), 60, Duration.ofHours(1));
        service.join(slug, user);
    }

    @DeleteMapping("/communities/{slug}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String slug, @AuthenticationPrincipal AuthUser user) {
        service.leave(slug, user);
    }

    @GetMapping("/communities/{slug}/members")
    public OffsetPage<MemberDto> members(@PathVariable String slug, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "30") int size,
                                         @AuthenticationPrincipal AuthUser user) {
        return service.members(slug, user, page, size);
    }

    @PutMapping("/communities/{slug}/members/{username}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setRole(@PathVariable String slug, @PathVariable String username,
                        @AuthenticationPrincipal AuthUser user, @Valid @RequestBody SetRoleRequest req) {
        service.setRole(slug, user, username, CommunityRole.valueOf(req.role()));
    }
}
