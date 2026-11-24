package app.howlers.admin;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Site administration DTOs (docs/API.md → Admin). Every count here is computed from real rows. */
public final class AdminDtos {
    private AdminDtos() {}

    public record DayCount(String day, long count) {}

    public record Overview(long users, long newUsers7d, long suspendedUsers, long communities, long quarantinedCommunities,
                           long bannedCommunities, long posts, long comments, long openReports, long openFlags,
                           List<DayCount> signups) {}

    public record AdminUser(long id, String username, String displayName, String avatarUrl, String email, String role,
                            String status, boolean emailVerified, Instant createdAt, int postKarma, int commentKarma,
                            long postCount, long commentCount, String suspendedReason, Instant suspendedAt) {}

    public record AuditEntry(long id, String action, UserSummary admin, UserSummary targetUser, CommunityRef targetCommunity,
                             Long flagId, String reason, Instant createdAt) {}

    public record AdminUserDetail(AdminUser user, List<CommunityRef> moderatorOf, List<AuditEntry> history) {}

    public record AdminCommunity(long id, String slug, String name, String iconUrl, String visibility, String status,
                                 String statusReason, int memberCount, int postCount, Instant createdAt, UserSummary owner,
                                 long openReports, long openFlags) {}

    public record AdminReport(CommunityRef community, String targetType, long postId, Long commentId, String title,
                              String excerpt, UserSummary author, long reportCount, List<String> reasons, boolean removed,
                              Instant lastReportedAt) {}

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {}

    public record OptionalReasonRequest(@Size(max = 300) String reason) {}

    public record RoleRequest(@NotNull @Pattern(regexp = "ADMIN|USER") String role, @Size(max = 300) String reason) {}

    public record CommunityStatusRequest(@NotNull @Pattern(regexp = "ACTIVE|QUARANTINED|BANNED") String status,
                                         @Size(max = 300) String reason) {}
}
