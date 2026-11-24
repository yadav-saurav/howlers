package app.howlers.user.dto;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** User-facing DTOs. Entities never leave the service layer. */
public final class UserDtos {
    private UserDtos() {}

    public record UserSummary(long id, String username, String displayName, String avatarUrl) {}

    public record Settings(String theme, boolean showNsfw, boolean profilePublic, boolean showCommunities,
                           boolean emailNotifications, String dmPolicy) {}

    public record Me(long id, String username, String email, String displayName, String avatarUrl, String bannerUrl,
                     String bio, boolean emailVerified, boolean onboardingCompleted, String role, int postKarma,
                     int commentKarma, Instant createdAt, Settings settings) {}

    public record PublicProfile(long id, String username, String displayName, String avatarUrl, String bannerUrl,
                                String bio, Instant createdAt, int postKarma, int commentKarma, int followerCount,
                                int followingCount, boolean isFollowing, boolean isSelf, boolean isPrivate,
                                boolean isBlocked, boolean canMessage, List<CommunityRef> communities,
                                List<CommunityRef> moderatorOf) {}

    public record UpdateProfileRequest(@Size(max = 50) String displayName, @Size(max = 500) String bio) {}

    public record UpdateSettingsRequest(@Pattern(regexp = "dark|light|system") String theme, Boolean showNsfw,
                                        Boolean profilePublic, Boolean showCommunities, Boolean emailNotifications,
                                        @Pattern(regexp = "EVERYONE|FOLLOWING|NOBODY") String dmPolicy) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 128)
                                        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain a letter and a digit")
                                        String newPassword) {}

    public record PasswordConfirmRequest(@NotBlank String password) {}
}
