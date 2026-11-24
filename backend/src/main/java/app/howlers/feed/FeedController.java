package app.howlers.feed;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.post.dto.PostDtos.PostSummary;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feeds")
@RestController
@RequestMapping("/api/v1")
public class FeedController {
    private final FeedService feeds;

    public FeedController(FeedService feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/feed")
    public CursorPage<PostSummary> feed(@RequestParam(defaultValue = "foryou") String tab,
                                        @RequestParam(required = false) String t,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit,
                                        @AuthenticationPrincipal AuthUser user) {
        return feeds.home(tab, t, cursor, Cursors.clampLimit(limit, 20, 50), user);
    }

    @GetMapping("/communities/{slug}/posts")
    public CursorPage<PostSummary> community(@PathVariable String slug, @RequestParam(defaultValue = "hot") String sort,
                                             @RequestParam(required = false) String t,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit,
                                             @AuthenticationPrincipal AuthUser user) {
        return feeds.community(slug, sort, t, cursor, Cursors.clampLimit(limit, 20, 50), user);
    }

    @GetMapping("/trending/posts")
    public List<PostSummary> trending(@RequestParam(defaultValue = "5") int limit, @AuthenticationPrincipal AuthUser user) {
        return feeds.trending(limit, user);
    }
}
