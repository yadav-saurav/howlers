package app.howlers.community;

/** Community-scoped roles, ordered from least to most privileged. */
public enum CommunityRole {
    MEMBER, CONTRIBUTOR, MODERATOR, ADMIN, OWNER;

    public boolean atLeast(CommunityRole other) {
        return ordinal() >= other.ordinal();
    }

    public boolean isModerator() {
        return atLeast(MODERATOR);
    }
}
