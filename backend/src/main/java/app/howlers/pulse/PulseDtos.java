package app.howlers.pulse;

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

public final class PulseDtos {
    private PulseDtos() {}

    public record OptionDto(long id, String label, Integer votes, boolean correct) {}

    public record PulseViewer(boolean canVote, boolean canManage) {}

    public record PulseDto(long id, CommunityRef community, String type, String title, String description, boolean multiple,
                           boolean anonymous, boolean allowChange, Instant closesAt, String status, Instant createdAt,
                           UserSummary author, int totalVoters, List<Long> myOptionIds, boolean resultsVisible,
                           List<OptionDto> options, Long resolvedOptionId, Double accuracy, PulseViewer viewer) {}

    public record PulseReason(UserSummary author, long optionId, String text, Instant createdAt) {}

    public record CreatePulse(@NotNull @Pattern(regexp = "POLL|PREDICTION|OPINION|DEBATE") String type,
                              @NotBlank @Size(max = 200) String title, @Size(max = 1000) String description,
                              @NotNull @Size(min = 2, max = 8) List<@NotBlank @Size(max = 120) String> options,
                              boolean multiple, boolean anonymous, boolean allowChange,
                              @NotNull @Min(1) @Max(720) Integer durationHours) {}

    public record VoteRequest(@NotNull @Size(min = 1, max = 8) List<Long> optionIds, @Size(max = 500) String reasoning) {}

    public record ResolveRequest(@NotNull Long optionId) {}
}
