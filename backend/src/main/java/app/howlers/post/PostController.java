package app.howlers.post;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.post.dto.PostDtos.PollDto;
import app.howlers.post.dto.PostDtos.PollVoteRequest;
import app.howlers.post.dto.PostDtos.PostDetail;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.post.dto.PostDtos.UpdatePostRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@Tag(name = "Posts")
@RestController
@RequestMapping("/api/v1")
public class PostController {
    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostDetail create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreatePostRequest req) {
        return posts.detail(posts.create(user, req), user);
    }

    @GetMapping("/posts/{id}")
    public PostDetail get(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return posts.detail(id, user);
    }

    @PatchMapping("/posts/{id}")
    public PostDetail update(@PathVariable long id, @AuthenticationPrincipal AuthUser user,
                             @Valid @RequestBody UpdatePostRequest req) {
        posts.update(id, user, req);
        return posts.detail(id, user);
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        posts.delete(id, user);
    }

    @PutMapping("/posts/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        posts.setSaved(id, user, true);
    }

    @DeleteMapping("/posts/{id}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        posts.setSaved(id, user, false);
    }

    @PutMapping("/posts/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        posts.setHidden(id, user, true);
    }

    @DeleteMapping("/posts/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhide(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        posts.setHidden(id, user, false);
    }

    @PostMapping("/posts/{id}/poll/vote")
    public PollDto votePoll(@PathVariable long id, @AuthenticationPrincipal AuthUser user,
                            @Valid @RequestBody PollVoteRequest req) {
        return posts.votePoll(id, user, req.optionIds());
    }

    @GetMapping("/me/saved")
    public CursorPage<PostSummary> saved(@AuthenticationPrincipal AuthUser user,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return posts.saved(user, cursor, Cursors.clampLimit(limit, 25, 50));
    }
}
