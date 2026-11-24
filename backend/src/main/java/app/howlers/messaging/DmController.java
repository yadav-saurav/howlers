package app.howlers.messaging;

import app.howlers.common.security.AuthUser;
import app.howlers.common.web.Cursors;
import app.howlers.messaging.DmDtos.ConversationSummary;
import app.howlers.messaging.DmDtos.DmMessage;
import app.howlers.messaging.DmDtos.MessagePage;
import app.howlers.messaging.DmDtos.ReadRequest;
import app.howlers.messaging.DmDtos.SendRequest;
import app.howlers.messaging.DmDtos.StartRequest;
import app.howlers.messaging.DmDtos.TypingRequest;
import app.howlers.realtime.StompPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Tag(name = "Messages")
@Controller
@ResponseBody
@RequestMapping("/api/v1/me")
public class DmController {
    private final DmService service;
    /** Last typing event per (user, conversation), to drop floods. */
    private final Map<String, Long> lastTyping = new ConcurrentHashMap<>();

    public DmController(DmService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> list(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q) {
        return service.list(user, q);
    }

    @PostMapping("/conversations")
    public ConversationSummary start(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody StartRequest req) {
        return service.start(user, req.username());
    }

    @GetMapping("/conversations/{id}")
    public ConversationSummary get(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return service.get(id, user);
    }

    @GetMapping("/conversations/{id}/messages")
    public MessagePage messages(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                                @RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
        return service.messages(id, user, before, Cursors.clampLimit(limit, 30, 100));
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public DmMessage send(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody SendRequest req) {
        return service.send(id, user, req);
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody ReadRequest req) {
        service.markRead(id, user, req.messageId());
    }

    @DeleteMapping("/messages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        service.delete(id, user);
    }

    @GetMapping("/messages/unread-count")
    public Map<String, Long> unread(@AuthenticationPrincipal AuthUser user) {
        return Map.of("count", service.unreadCount(user.id()));
    }

    /** STOMP {@code SEND /app/typing {conversationId}}. At most one forwarded event per second per conversation. */
    @MessageMapping("/typing")
    public void typing(TypingRequest req, Principal principal) {
        if (!(principal instanceof StompPrincipal sp) || req == null || req.conversationId() == null) return;
        String key = sp.user().id() + ":" + req.conversationId();
        long now = System.currentTimeMillis();
        Long prev = lastTyping.get(key);
        if (prev != null && now - prev < 1000) return;
        lastTyping.put(key, now);
        if (lastTyping.size() > 10_000) lastTyping.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        service.typing(req.conversationId(), sp.user());
    }
}
