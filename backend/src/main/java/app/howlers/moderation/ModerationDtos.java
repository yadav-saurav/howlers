package app.howlers.moderation;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ModerationDtos {
    private ModerationDtos() {}

    public enum Action { REMOVE_POST, APPROVE_POST, REMOVE_COMMENT, APPROVE_COMMENT, LOCK, UNLOCK, PIN, UNPIN, BAN, UNBAN }

    public record ReportRequest(@NotNull @Pattern(regexp = "POST|COMMENT") String targetType, @NotNull Long postId,
                                Long commentId, @NotBlank @Size(max = 100) String reason, @Size(max = 1000) String details) {}

    public record ReasonCount(String reason, int count) {}

    public record QueueItem(String targetType, long postId, Long commentId, String title, String excerpt, UserSummary author,
                            boolean removed, boolean locked, boolean pinned, Instant createdAt, int reportCount,
                            List<ReasonCount> reasons, List<String> details, Instant firstReportedAt, Instant lastReportedAt) {}

    public record ModAction(long id, String action, UserSummary moderator, UserSummary targetUser, Long postId, Long commentId,
                            String targetTitle, String reason, Instant expiresAt, boolean reversed, Instant createdAt) {}

    public record Ban(UserSummary user, String reason, Instant expiresAt, UserSummary bannedBy, Instant createdAt) {}

    public record Appeal(long id, ModAction action, UserSummary user, String message, String status, String response,
                         Instant createdAt, Instant resolvedAt) {}

    public record ModStats(long openReports, long pendingAppeals, long activeBans, long actionsLast30Days, long removedLast30Days,
                           long heldForReview) {}

    /** One UTC day of community activity for the moderator dashboard chart. */
    public record DayActivity(String day, long posts, long comments, long joins) {}

    /** The community owner's dashboard: size, activity and safety numbers, with the last 14 days per day. */
    public record ModOverview(long members, long newMembers7d, long posts30d, long comments30d, long removals30d,
                              long automodHits30d, long openReports, long heldForReview, long pendingAppeals,
                              long activeBans, List<DayActivity> days) {}

    public record MyModAction(ModAction action, CommunityRef community, Appeal appeal, boolean canAppeal) {}

    public record ReasonRequest(@Size(max = 300) String reason) {}

    public record BanRequest(@NotBlank @Size(max = 21) String username, @NotBlank @Size(max = 300) String reason,
                             @Min(1) @Max(3650) Integer days) {}

    public record ResolveAppealRequest(@NotNull Boolean accept, @Size(max = 1000) String response) {}

    public record AppealRequest(@NotBlank @Size(min = 10, max = 2000) String message) {}
}
