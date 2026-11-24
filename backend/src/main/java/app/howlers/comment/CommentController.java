package app.howlers.comment;

import app.howlers.comment.dto.CommentDtos.CommentNode;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.comment.dto.CommentDtos.UpdateCommentRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Comments")
@RestController
@RequestMapping("/api/v1")
public class CommentController {
    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/posts/{postId}/comments")
    public CursorPage<CommentNode> list(@PathVariable long postId, @RequestParam(defaultValue = "best") String sort,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit,
                                        @AuthenticationPrincipal AuthUser user) {
        return comments.topLevel(postId, user, sort, cursor, Cursors.clampLimit(limit, 20, 50));
    }

    @GetMapping("/comments/{id}/replies")
    public CursorPage<CommentNode> replies(@PathVariable long id, @RequestParam(defaultValue = "best") String sort,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit,
                                           @AuthenticationPrincipal AuthUser user) {
        return comments.replies(id, user, sort, cursor, Cursors.clampLimit(limit, 10, 50));
    }

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentNode create(@PathVariable long postId, @AuthenticationPrincipal AuthUser user,
                              @Valid @RequestBody CreateCommentRequest req) {
        return comments.create(postId, user, req);
    }

    @GetMapping("/comments/{id}")
    public CommentNode one(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        return comments.one(id, user);
    }

    @PatchMapping("/comments/{id}")
    public CommentNode update(@PathVariable long id, @AuthenticationPrincipal AuthUser user,
                              @Valid @RequestBody UpdateCommentRequest req) {
        return comments.update(id, user, req.body());
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AuthUser user) {
        comments.delete(id, user);
    }
}
