package app.howlers.search;

import app.howlers.common.ratelimit.RateLimiter;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search")
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private final SearchService search;
    private final RateLimiter limiter;

    public SearchController(SearchService search, RateLimiter limiter) {
        this.search = search;
        this.limiter = limiter;
    }

    @GetMapping
    public CursorPage<?> search(@RequestParam String q, @RequestParam(defaultValue = "posts") String type,
                                @RequestParam(required = false) String community,
                                @RequestParam(defaultValue = "relevance") String sort,
                                @RequestParam(required = false) String t,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit,
                                @AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        limiter.check("search:" + (user != null ? "u" + user.id() : req.getRemoteAddr()), 60, Duration.ofMinutes(1));
        int l = Cursors.clampLimit(limit, 20, 50);
        return switch (type) {
            case "communities" -> search.communities(q, cursor, l, user);
            case "users" -> search.users(q, cursor, l);
            default -> search.posts(q, community, sort, t, cursor, l, user);
        };
    }

    @GetMapping("/suggest")
    public SearchService.Suggestions suggest(@RequestParam String q, @AuthenticationPrincipal AuthUser user,
                                             HttpServletRequest req) {
        limiter.check("suggest:" + (user != null ? "u" + user.id() : req.getRemoteAddr()), 120, Duration.ofMinutes(1));
        return search.suggest(q, user);
    }
}
