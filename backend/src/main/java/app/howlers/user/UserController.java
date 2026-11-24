package app.howlers.user;

import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.UserComment;
import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.feed.FeedService;
import app.howlers.post.dto.PostDtos.PostSummary;
import app.howlers.user.dto.UserDtos.ChangePasswordRequest;
import app.howlers.user.dto.UserDtos.Me;
import app.howlers.user.dto.UserDtos.PasswordConfirmRequest;
import app.howlers.user.dto.UserDtos.PublicProfile;
import app.howlers.user.dto.UserDtos.UpdateProfileRequest;
import app.howlers.user.dto.UserDtos.UpdateSettingsRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;
    private final FeedService feeds;
    private final CommentService comments;

    public UserController(UserService users, FeedService feeds, CommentService comments) {
        this.users = users;
        this.feeds = feeds;
        this.comments = comments;
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AuthUser user) {
        return users.me(user);
    }

    @PatchMapping("/me")
    public Me update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return users.updateProfile(user, req);
    }

    @PatchMapping("/me/settings")
    public Me settings(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateSettingsRequest req) {
        return users.updateSettings(user, req);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Me avatar(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        return users.uploadImage(user, file, false);
    }

    @PostMapping(value = "/me/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Me banner(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        return users.uploadImage(user, file, true);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePasswordRequest req) {
        users.changePassword(user, req.currentPassword(), req.newPassword());
    }

    @PostMapping("/me/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PasswordConfirmRequest req) {
        users.deactivate(user, req.password());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PasswordConfirmRequest req) {
        users.delete(user, req.password());
    }

    @GetMapping("/{username}")
    public PublicProfile profile(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        return users.profile(username, user);
    }

    @GetMapping("/{username}/posts")
    public CursorPage<PostSummary> posts(@PathVariable String username, @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit,
                                         @AuthenticationPrincipal AuthUser user) {
        User u = users.requireByUsername(username);
        users.requireActivityVisible(u, user);
        return feeds.byAuthor(u.getId(), cursor, Cursors.clampLimit(limit, 20, 50), user);
    }

    @GetMapping("/{username}/comments")
    public CursorPage<UserComment> comments(@PathVariable String username, @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit,
                                            @AuthenticationPrincipal AuthUser user) {
        User u = users.requireByUsername(username);
        users.requireActivityVisible(u, user);
        return comments.byUser(u.getId(), user, cursor, Cursors.clampLimit(limit, 20, 50));
    }

    @PostMapping("/{username}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        users.follow(user, username);
    }

    @DeleteMapping("/{username}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable String username, @AuthenticationPrincipal AuthUser user) {
        users.unfollow(user, username);
    }
}
