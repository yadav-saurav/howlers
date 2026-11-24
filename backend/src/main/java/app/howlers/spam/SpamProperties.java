package app.howlers.spam;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds for the write-time spam heuristics ({@code howlers.spam.*}). Mutable on purpose: integration tests switch
 * a heuristic on, tighten its thresholds and restore them afterwards without starting a new application context.
 * A "hold" sends content to review; a "reject" refuses it outright. Rejection thresholds are deliberately far above
 * the hold thresholds so that only clearly abusive content is refused.
 */
@ConfigurationProperties(prefix = "howlers.spam")
public class SpamProperties {
    private boolean enabled = true;
    /** Accounts younger than this are "new" for the link-velocity and burst rules. */
    private Duration newAccountAge = Duration.ofDays(7);
    private LinkVelocity linkVelocity = new LinkVelocity();
    private Duplicate duplicate = new Duplicate();
    private Burst burst = new Burst();
    private Mentions mentions = new Mentions();

    public static class LinkVelocity {
        private boolean enabled = true;
        private int maxLinks = 5;
        private int rejectLinks = 20;
        private Duration window = Duration.ofMinutes(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxLinks() { return maxLinks; }
        public void setMaxLinks(int maxLinks) { this.maxLinks = maxLinks; }
        public int getRejectLinks() { return rejectLinks; }
        public void setRejectLinks(int rejectLinks) { this.rejectLinks = rejectLinks; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Duplicate {
        private boolean enabled = true;
        /** Same content in this many distinct communities (counting the new one) within the window is held. */
        private int maxCommunities = 3;
        private int rejectCommunities = 6;
        private Duration window = Duration.ofHours(1);
        /** Bodies at least this long also match across different accounts; shorter ones only for the same author. */
        private int crossAccountMinBody = 40;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxCommunities() { return maxCommunities; }
        public void setMaxCommunities(int maxCommunities) { this.maxCommunities = maxCommunities; }
        public int getRejectCommunities() { return rejectCommunities; }
        public void setRejectCommunities(int rejectCommunities) { this.rejectCommunities = rejectCommunities; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public int getCrossAccountMinBody() { return crossAccountMinBody; }
        public void setCrossAccountMinBody(int crossAccountMinBody) { this.crossAccountMinBody = crossAccountMinBody; }
    }

    public static class Burst {
        private boolean enabled = true;
        /** Posts + comments by a new account within the window, counting the new one. */
        private int maxItems = 8;
        private int rejectItems = 30;
        private Duration window = Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxItems() { return maxItems; }
        public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
        public int getRejectItems() { return rejectItems; }
        public void setRejectItems(int rejectItems) { this.rejectItems = rejectItems; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Mentions {
        private boolean enabled = true;
        /** More distinct @mentions than this in one post or comment is held. */
        private int max = 8;
        private int reject = 25;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getReject() { return reject; }
        public void setReject(int reject) { this.reject = reject; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getNewAccountAge() { return newAccountAge; }
    public void setNewAccountAge(Duration newAccountAge) { this.newAccountAge = newAccountAge; }
    public LinkVelocity getLinkVelocity() { return linkVelocity; }
    public void setLinkVelocity(LinkVelocity linkVelocity) { this.linkVelocity = linkVelocity; }
    public Duplicate getDuplicate() { return duplicate; }
    public void setDuplicate(Duplicate duplicate) { this.duplicate = duplicate; }
    public Burst getBurst() { return burst; }
    public void setBurst(Burst burst) { this.burst = burst; }
    public Mentions getMentions() { return mentions; }
    public void setMentions(Mentions mentions) { this.mentions = mentions; }
}
