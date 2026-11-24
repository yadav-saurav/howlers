package app.howlers.reputation;

import app.howlers.topic.Topic.TopicDto;
import app.howlers.user.dto.UserDtos.UserSummary;
import java.time.Instant;
import java.util.List;

public final class ReputationDtos {
    private ReputationDtos() {}

    /** The live constants of {@link ReputationMath}, so the UI explains the rules with the real numbers. */
    public record Policy(double upvote, double downvote, int pairDailyCap, double topicDailyCap, int newAccountDays,
                         double newAccountWeight, int minKarma, double lowKarmaWeight, double qualitySaturation,
                         List<TierInfo> tiers, int topRank, double topMinPoints) {
        public static Policy current() {
            return new Policy(ReputationMath.UP, ReputationMath.DOWN, ReputationMath.PAIR_DAILY_CAP, ReputationMath.TOPIC_DAILY_CAP,
                    ReputationMath.NEW_ACCOUNT_DAYS, ReputationMath.NEW_ACCOUNT_TRUST, ReputationMath.MIN_KARMA,
                    ReputationMath.LOW_KARMA_TRUST, ReputationMath.QUALITY_SATURATION,
                    java.util.Arrays.stream(ReputationMath.Tier.values()).filter(t -> t != ReputationMath.Tier.NONE)
                            .map(t -> new TierInfo(t.name(), t.label, t.min)).toList(),
                    ReputationMath.TOP_RANK, ReputationMath.TOP_MIN_POINTS);
        }
    }

    public record TierInfo(String tier, String label, double minPoints) {}

    public record LeaderEntry(int rank, UserSummary user, double points, String tier, boolean topContributor,
                              int upvotes, int downvotes) {}

    public record TopicLeaderboard(TopicDto topic, List<LeaderEntry> entries, long contributors, Instant computedAt,
                                   Policy policy) {}

    public record TopicOverview(TopicDto topic, long contributors, List<LeaderEntry> leaders) {}

    public record ReputationOverview(List<TopicOverview> topics, Instant computedAt) {}

    public record TopicStanding(TopicDto topic, double points, String tier, int rank, boolean topContributor,
                                int upvotes, int downvotes) {}

    /** {@code hidden} when the profile is private and the viewer isn't its owner. */
    public record UserReputation(boolean hidden, List<TopicStanding> topics, Instant computedAt) {}

    public record Expertise(long userId, String topicSlug, String topicName, double points, String tier, boolean topContributor) {}

    public record PostExpertise(List<TopicDto> topics, List<Expertise> authors) {}
}
