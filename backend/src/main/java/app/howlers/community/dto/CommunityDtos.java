package app.howlers.community.dto;

import app.howlers.topic.Topic.TopicDto;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CommunityDtos {
    private CommunityDtos() {}

    public static final String SLUG_REGEX = "^[a-z0-9_]{3,24}$";
    public static final String TAG_REGEX = "^[a-z0-9-]{1,24}$";

    public record CommunityRef(long id, String slug, String name, String iconUrl) {}

    public record FlairDto(long id, String text, String color) {}

    public record RuleDto(long id, String title, String description, int position) {}

    public record ModeratorDto(UserSummary user, String role) {}

    public record ViewerDto(boolean isMember, String role, boolean canPost, boolean canModerate, boolean canView,
                            boolean banned, Instant bannedUntil) {}

    public record CommunitySummary(long id, String slug, String name, String description, String iconUrl,
                                   String bannerUrl, TopicDto topic, String visibility, int memberCount,
                                   List<String> tags, boolean isMember) {}

    public record CommunityDetail(long id, String slug, String name, String description, String iconUrl,
                                  String bannerUrl, TopicDto topic, String visibility, int memberCount,
                                  List<String> tags, boolean isMember, Instant createdAt, List<RuleDto> rules,
                                  List<FlairDto> flairs, List<ModeratorDto> moderators, ViewerDto viewer,
                                  String status, String statusReason) {}

    public record MemberDto(UserSummary user, String role, Instant joinedAt) {}

    public record RuleInput(@NotBlank @Size(max = 100) String title, @Size(max = 500) String description) {}

    public record FlairInput(Long id, @NotBlank @Size(max = 40) String text,
                             @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a hex color") String color) {}

    public record CreateCommunityRequest(
            @NotBlank @Pattern(regexp = SLUG_REGEX, message = "3-24 lowercase letters, digits or underscores") String slug,
            @NotBlank @Size(max = 60) String name,
            @Size(max = 1000) String description,
            Long topicId,
            @NotNull @Pattern(regexp = "PUBLIC|RESTRICTED|PRIVATE") String visibility,
            @Size(max = 8) List<@Pattern(regexp = TAG_REGEX, message = "tags are lowercase words with dashes") String> tags,
            @Size(max = 15) List<@Valid RuleInput> rules) {}

    public record UpdateCommunityRequest(@Size(min = 1, max = 60) String name, @Size(max = 1000) String description,
                                         Long topicId, @Pattern(regexp = "PUBLIC|RESTRICTED|PRIVATE") String visibility,
                                         @Size(max = 8) List<@Pattern(regexp = TAG_REGEX) String> tags) {}

    public record SetRoleRequest(@NotNull @Pattern(regexp = "ADMIN|MODERATOR|CONTRIBUTOR|MEMBER") String role) {}
}
