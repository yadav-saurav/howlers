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
    /**
     * {@code kind} is IMAGE or AUDIO (a voice note: no size or thumbnails, {@code durationMs} as recorded);
     * {@code contentType} is the stored type the URL is served with.
     */
    public record DmMedia(long id, String url, Integer width, Integer height, app.howlers.storage.Thumbnails thumbnails,
                          boolean signed, java.time.Instant expiresAt, String kind, String contentType, Integer durationMs) {}

    /**
     * {@code kind} is USER or SYSTEM (pack-room events such as "mira_k added dev_ostrander", rendered as text in
     * {@code body}). {@code fromBlocked}: in a pack room, a message from someone you blocked, sent with no body and no
     * media so the client shows it collapsed (D-043). {@code replyTo} is null unless the message is a reply (and for
     * deleted or collapsed messages).
     */
    public record DmMessage(long id, long conversationId, Long senderId, String body, List<DmMedia> media,
                            Instant createdAt, boolean deleted, String kind, boolean fromBlocked, ReplyPreview replyTo) {}

    /**
     * The message a reply points at: {@code excerpt} is its first ~120 characters ("" when it had no text, was deleted,
     * or is from someone you blocked in a pack room); {@code mediaKind} is null, IMAGE or AUDIO.
     */
    public record ReplyPreview(long id, Long senderId, String excerpt, boolean deleted, String mediaKind) {}

    public record LastMessage(long id, Long senderId, String excerpt, Instant createdAt, boolean deleted) {}

    /**
     * {@code kind} is DIRECT or ROOM. For a ROOM, {@code other} and {@code otherLastReadMessageId} are null,
     * {@code online} means "another member is online", and {@code room} carries the name, icon and your role.
     * {@code favorite} is your own flag; {@code theme} is shared by everyone in the conversation (null = default).
     */
    public record ConversationSummary(long id, UserSummary other, LastMessage lastMessage, long unreadCount,
                                      Long otherLastReadMessageId, boolean online, boolean canMessage,
                                      String cannotMessageReason, Instant updatedAt, String kind, RoomInfo room,
                                      boolean favorite, String theme) {}

    public record RoomInfo(String name, String iconEmoji, int memberCount, String myRole) {}

    public record MessagePage(List<DmMessage> items, boolean hasMore) {}

    public record StartRequest(@NotBlank @Size(max = 21) String username) {}

    /** {@code replyToId}: optional, a message in the same conversation. */
    public record SendRequest(@Size(max = 4000) String body, @Size(max = 4) List<Long> mediaIds, Long replyToId) {
        public SendRequest(String body, List<Long> mediaIds) {
            this(body, mediaIds, null);
        }
    }

    public record FavoriteRequest(@NotNull Boolean favorite) {}

    /** {@code null} or "" resets to the default theme. */
    public record ThemeRequest(String theme) {}

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
