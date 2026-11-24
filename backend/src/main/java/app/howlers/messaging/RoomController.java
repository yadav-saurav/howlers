package app.howlers.messaging;

import app.howlers.common.security.AuthUser;
import app.howlers.messaging.DmDtos.AddMembersRequest;
import app.howlers.messaging.DmDtos.ConversationSummary;
import app.howlers.messaging.DmDtos.CreateRoomRequest;
import app.howlers.messaging.DmDtos.RoleRequest;
import app.howlers.messaging.DmDtos.RoomDetail;
import app.howlers.messaging.DmDtos.TransferRequest;
import app.howlers.messaging.DmDtos.UpdateRoomRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pack rooms (D-043). Messages, receipts, typing and deletion use the conversation endpoints in {@link DmController};
 * these manage the room itself.
 */
@Tag(name = "Messages")
@RestController
@RequestMapping("/api/v1/me/rooms")
public class RoomController {
    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummary create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateRoomRequest req) {
        return rooms.create(user, req.name(), req.iconEmoji(), req.usernames());
    }

    @GetMapping("/{id}")
    public RoomDetail get(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return rooms.detail(id, user);
    }

    @PatchMapping("/{id}")
    public RoomDetail update(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody UpdateRoomRequest req) {
        return rooms.update(id, user, req.name(), req.iconEmoji());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        rooms.delete(id, user);
    }

    @PostMapping("/{id}/members")
    public RoomDetail add(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody AddMembersRequest req) {
        return rooms.addMembers(id, user, req.usernames());
    }

    @DeleteMapping("/{id}/members/{userId}")
    public RoomDetail remove(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @PathVariable long userId) {
        return rooms.removeMember(id, user, userId);
    }

    @PutMapping("/{id}/members/{userId}/role")
    public RoomDetail role(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @PathVariable long userId,
                           @Valid @RequestBody RoleRequest req) {
        return rooms.setRole(id, user, userId, req.role());
    }

    @PostMapping("/{id}/transfer")
    public RoomDetail transfer(@AuthenticationPrincipal AuthUser user, @PathVariable long id, @Valid @RequestBody TransferRequest req) {
        return rooms.transfer(id, user, req.userId());
    }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        rooms.leave(id, user);
    }
}
