package app.howlers.messaging;

import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class DmDtos {
    private DmDtos() {}

    /**
     * {@code url} is a participant-only signed URL for images uploaded through {@code /me/dm-media} (valid 30 to 60 min;
     * refetch the messages for fresh ones), or the legacy public media URL for older attachments (D-024).
     */
    public record DmMedia(long id, String url, Integer width, Integer height, app.howlers.storage.Thumbnails thumbnails,
                          boolean signed, java.time.Instant expiresAt) {}

    /**
     * {@code kind} is USER or SYSTEM (pack-room events such as "mira_k added dev_ostrander", rendered as text in
     * {@code body}). {@code fromBlocked}: in a pack room, a message from someone you blocked, sent with no body and no
     * media so the client shows it collapsed (D-043).
     */
    public record DmMessage(long id, long conversationId, Long senderId, String body, List<DmMedia> media,
                            Instant createdAt, boolean deleted, String kind, boolean fromBlocked) {}

    public record LastMessage(long id, Long senderId, String excerpt, Instant createdAt, boolean deleted) {}

    /**
     * {@code kind} is DIRECT or ROOM. For a ROOM, {@code other} and {@code otherLastReadMessageId} are null,
     * {@code online} means "another member is online", and {@code room} carries the name, icon and your role.
     */
    public record ConversationSummary(long id, UserSummary other, LastMessage lastMessage, long unreadCount,
                                      Long otherLastReadMessageId, boolean online, boolean canMessage,
                                      String cannotMessageReason, Instant updatedAt, String kind, RoomInfo room) {}

    public record RoomInfo(String name, String iconEmoji, int memberCount, String myRole) {}

    public record MessagePage(List<DmMessage> items, boolean hasMore) {}

    public record StartRequest(@NotBlank @Size(max = 21) String username) {}

    public record SendRequest(@Size(max = 4000) String body, @Size(max = 4) List<Long> mediaIds) {}

    public record ReadRequest(@NotNull Long messageId) {}

    public record TypingRequest(Long conversationId) {}

    // ---------------------------------------------------------------- pack rooms (D-043)

    public record RoomMember(UserSummary user, String role, Instant joinedAt, Long lastReadMessageId, boolean online) {}

    /** Members are listed owner first, then admins, then by join time. */
    public record RoomDetail(long id, String name, String iconEmoji, String myRole, List<RoomMember> members,
                             int maxMembers, Instant createdAt) {}

    public record CreateRoomRequest(@NotBlank @Size(max = 60) String name, @Size(max = 16) String iconEmoji,
                                    @NotNull @Size(min = 2, max = 49) List<@NotBlank @Size(max = 21) String> usernames) {}

    /** Both optional; an empty {@code iconEmoji} clears the icon. */
    public record UpdateRoomRequest(@Size(max = 60) String name, @Size(max = 16) String iconEmoji) {}

    public record AddMembersRequest(@NotNull @Size(min = 1, max = 48) List<@NotBlank @Size(max = 21) String> usernames) {}

    public record RoleRequest(@NotBlank String role) {}

    public record TransferRequest(@NotNull Long userId) {}
}
