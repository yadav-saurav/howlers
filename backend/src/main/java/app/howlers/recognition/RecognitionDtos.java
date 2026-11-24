package app.howlers.recognition;

import app.howlers.community.dto.CommunityDtos.CommunityRef;
import app.howlers.recognition.ModeratorScoreMath.Badge;
import app.howlers.user.dto.UserDtos.UserSummary;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class RecognitionDtos {
    private RecognitionDtos() {}

    public record Components(Double consistency, Double responsiveness, Double appealQuality, Double health, Double peer) {}

    public record Inputs(int decisions, int decisionsReversed, int reportsHandled, Double medianResponseMinutes,
                         int appealsUpheld, int appealsOverturned, int endorsements, int coModerators,
                         int communityReports, int communityReportsResolved, Double removalRate, Double prevRemovalRate) {}

    public record BadgeDto(String code, String label, String rule) {
        public static BadgeDto of(String code) {
            Badge b = Badge.valueOf(code);
            return new BadgeDto(b.name(), b.label, b.rule);
        }
    }

    /** rank is null when the row isn't ranked (opted out, or below the activity gate). */
    public record ModeratorScore(UserSummary moderator, CommunityRef community, String period, Integer rank, double score,
                                 boolean ranked, Components components, Inputs inputs, List<BadgeDto> badges,
                                 Instant computedAt) {}

    public record Weight(String component, String label, double weight, String definition) {}

    /** The formula, served from the same constants the job uses, so the page can't drift from the math. */
    public record Formula(List<Weight> weights, int minActivity, double responseHalfHours, int peerFullAt,
                          List<BadgeDto> badges, String note) {
        public static Formula current() {
            return new Formula(List.of(
                    new Weight("consistency", "Consistency", ModeratorScoreMath.W_CONSISTENCY,
                            "1 − (removals and bans later overturned on appeal or undone by another moderator ÷ removals and bans made)"),
                    new Weight("responsiveness", "Response time", ModeratorScoreMath.W_RESPONSIVENESS,
                            "1 ÷ (1 + median hours from report to resolution ÷ " + (int) ModeratorScoreMath.RESPONSE_HALF_HOURS + ")"),
                    new Weight("appealQuality", "Appeal outcomes", ModeratorScoreMath.W_APPEALS,
                            "appeals upheld ÷ appeals decided, for appeals against this moderator's actions"),
                    new Weight("health", "Community health", ModeratorScoreMath.W_HEALTH,
                            "0.7 × share of the period's reports resolved + 0.3 × removal-rate trend (removals per new post or comment, vs the previous period; 0.5 = unchanged)"),
                    new Weight("peer", "Peer feedback", ModeratorScoreMath.W_PEER,
                            "endorsements from co-moderators ÷ min(" + ModeratorScoreMath.PEER_FULL_AT + ", number of co-moderators), capped at 1")),
                    ModeratorScoreMath.MIN_ACTIVITY, ModeratorScoreMath.RESPONSE_HALF_HOURS, ModeratorScoreMath.PEER_FULL_AT,
                    Arrays.stream(Badge.values()).map(b -> BadgeDto.of(b.name())).toList(),
                    "Score = 100 × weighted mean of the components that exist this period (missing ones drop out). "
                            + "The number of removals is never a ranking input; activity only decides whether there is enough to rank.");
        }
    }

    public record Leaderboard(String period, List<String> periods, CommunityRef community, List<ModeratorScore> entries,
                              Formula formula, Instant computedAt) {}

    public record ModerationRecord(boolean hidden, boolean optedOut, List<ModeratorScore> entries, Formula formula) {}

    public record Peer(UserSummary moderator, boolean endorsedByMe, String note, int endorsementsThisPeriod) {}

    public record Peers(String period, CommunityRef community, List<Peer> peers) {}

    public record EndorseRequest(@Size(max = 280) String note) {}

    public record ModeratorSettings(@NotNull Boolean publicRanking) {}
}
