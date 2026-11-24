package app.howlers.notification;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.CursorPage;
import app.howlers.common.web.Cursors;
import app.howlers.notification.NotificationService.NotificationDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public CursorPage<NotificationDto> list(@AuthenticationPrincipal AuthUser user,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.list(user.id(), cursor, Cursors.clampLimit(limit, 25, 50), unreadOnly);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread(@AuthenticationPrincipal AuthUser user) {
        return Map.of("count", service.unreadCount(user.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        service.markRead(user.id(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal AuthUser user) {
        service.markAllRead(user.id());
    }
}
