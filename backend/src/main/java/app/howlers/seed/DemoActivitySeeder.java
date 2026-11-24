package app.howlers.seed;

import app.howlers.collection.CollectionDtos.AddItem;
import app.howlers.collection.CollectionDtos.UpdateCollection;
import app.howlers.collection.CollectionService;
import app.howlers.comment.CommentService;
import app.howlers.comment.dto.CommentDtos.CreateCommentRequest;
import app.howlers.common.config.AppProperties;
import app.howlers.common.security.AuthUser;
import app.howlers.funding.FundingDtos.ContributeRequest;
import app.howlers.funding.FundingDtos.CreatePoolRequest;
import app.howlers.funding.FundingDtos.PolicyRequest;
import app.howlers.funding.FundingService;
import app.howlers.moderation.ModerationDtos.Action;
import app.howlers.moderation.ModerationDtos.ReportRequest;
import app.howlers.moderation.ModerationService;
import app.howlers.recognition.RecognitionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Demo moderation history, public collections and a demo funding pool on top of {@link DemoDataSeeder} (Phase 10).
 * Everything goes through the real services as the demo accounts, so the rows are exactly what the product would
 * write: reports resolve through the mod tools, the moderator leaderboard is recomputed from those rows, and funding
 * runs through the demo payment provider (every amount is flagged demo). Runs once (marker: mira_k's "Engineering
 * reading list" collection), also on databases seeded before this existed. Switch off with
 * {@code howlers.seed.activity=false}; the whole seed is off with {@code howlers.seed.enabled=false}.
 */
@Component
@Order(100) // after DemoDataSeeder (@Order(10)) and AdminDemoSeeder
public class DemoActivitySeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoActivitySeeder.class);
    static final String MARKER_COLLECTION = "Engineering reading list";

    private final JdbcClient jdbc;
    private final CommentService comments;
    private final ModerationService moderation;
    private final CollectionService collections;
    private final FundingService funding;
    private final RecognitionService recognition;
    private final boolean enabled;

    public DemoActivitySeeder(JdbcClient jdbc, CommentService comments, ModerationService moderation, CollectionService collections,
                              FundingService funding, RecognitionService recognition, AppProperties props,
                              @Value("${howlers.seed.activity:true}") boolean activity) {
        this.jdbc = jdbc;
        this.comments = comments;
        this.moderation = moderation;
        this.collections = collections;
        this.funding = funding;
        this.recognition = recognition;
        this.enabled = props.seed().enabled() && activity;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) seedIfNeeded();
    }

    /** @return true when it seeded now. */
    public boolean seedIfNeeded() {
        Optional<AuthUser> mira = user("mira_k");
        if (mira.isEmpty()) return false; // base demo data isn't there (seed off, or a real database)
        boolean done = jdbc.sql("SELECT EXISTS (SELECT 1 FROM collections WHERE owner_id = ? AND name = ?)")
                .params(mira.get().id(), MARKER_COLLECTION).query(Boolean.class).single();
        if (done) return false;
        moderationHistory();
        collections();
        fundingPool();
        recognition.recompute(RecognitionService.currentPeriod(), 0);
        log.info("Seeded demo moderation history, collections and a demo funding pool");
        return true;
    }

    // ---------------------------------------------------------------- moderation

    private void moderationHistory() {
        // Moderator teams: c/programming and c/careers already have a co-moderator; c/cybersecurity gets one.
        promote("cybersecurity", "dev_ostrander");

        // c/programming: a course plug and a link-drop reported by readers; one removal is appealed and upheld,
        // one report turns out fine and is approved, one removal is overturned on appeal.
        long rustVsGo = post("programming", "After a year maintaining services in both Rust and Go");
        long keyset = post("programming", "Keyset pagination cut our feed endpoint");
        long plug = comment(rustVsGo, "reel_ramon",
                "If you want to learn both properly, my video course covers Rust and Go end to end. 40% off this week with code RUSTGO.");
        report(rustVsGo, plug, "paperback_jo", "Spam or self-promotion", "Course ad, not part of the discussion.", 95);
        report(rustVsGo, plug, "lena_lab", "Spam or self-promotion", "", 80);
        long plugRemoval = remove(plug, "rustacean_ali", "Rule: no self-promotion. Share what you learned, not a discount code.");
        appeal(plugRemoval, "reel_ramon", "The course is free for students, I thought it would help people here.",
                "mira_k", false, "Thanks for explaining. Discount codes still count as promotion here; a write-up of what you teach would be welcome.");

        long hot = comment(keyset, "coach_ines", "Offset pagination is fine for everyone, this is premature optimisation.");
        report(keyset, hot, "grid_and_type", "Rude or hostile", "Feels dismissive.", 140);
        approve(hot, "rustacean_ali"); // disagreement isn't a rule violation

        long dupe = comment(keyset, "tunde_builds", "We hit the same wall. Is the cursor encoded per sort, or one format for all of them?");
        report(keyset, dupe, "reel_ramon", "Off-topic", "", 60);
        long dupeRemoval = remove(dupe, "mira_k", "Removed as off-topic.");
        appeal(dupeRemoval, "tunde_builds", "This is a direct question about the cursor format in the post.",
                "rustacean_ali", true, "You're right, it's on topic. Restored, sorry about that.");

        // c/careers: two reports handled by the co-moderator.
        long slow = post("careers", "Six months into my first job and I still feel slow");
        long dm = comment(slow, "reel_ramon", "DM me, I sell a resume template pack that fixes this.");
        report(slow, dm, "first_job_fern", "Spam or self-promotion", "", 45);
        report(slow, dm, "coach_ines", "Spam or self-promotion", "", 30);
        remove(dm, "tunde_builds", "Rule: no selling in comments.");
        long blunt = comment(slow, "dev_ostrander", "Honestly, nobody feels fast at six months. Write down what you learn each week.");
        report(slow, blunt, "paperback_jo", "Other", "Not sure this answers the question.", 200);
        approve(blunt, "tunde_builds");
        long off = comment(slow, "grid_and_type", "Unrelated, but does anyone know a good monitor for design work?");
        report(slow, off, "lena_lab", "Off-topic", "", 25);
        remove(off, "quant_priya", "Off-topic for this thread; try the weekly open thread.");

        // c/cybersecurity: the new co-moderator works through a small queue.
        long roadmap = post("cybersecurity", "The beginner roadmap I wish someone had given me");
        long tool = comment(roadmap, "reel_ramon", "Skip all this, just buy a hacking course bundle and you'll be job-ready in a month.");
        report(roadmap, tool, "nadia_sec", "Misinformation", "Promises an outcome no course can.", 70);
        report(roadmap, tool, "first_job_fern", "Spam or self-promotion", "", 50);
        remove(tool, "dev_ostrander", "Removed: misleading claim and course promotion.");
        long nitpick = comment(roadmap, "paperback_jo", "I'd put Linux basics before networking, but this is a great list.");
        report(roadmap, nitpick, "coach_ines", "Other", "", 35);
        approve(nitpick, "dev_ostrander");
        long phish = comment(roadmap, "tunde_builds", "Free certification vouchers here: bit.ly/free-cert-vouchers");
        report(roadmap, phish, "quant_priya", "Spam or self-promotion", "Suspicious shortened link.", 20);
        remove(phish, "nadia_sec", "Removed: shortened link to an unknown site.");
    }

    private void promote(String slug, String username) {
        jdbc.sql("""
                        INSERT INTO community_members (community_id, user_id, role)
                        SELECT c.id, u.id, 'MODERATOR' FROM communities c, users u WHERE c.slug = ? AND u.username = ?
                        ON CONFLICT (community_id, user_id) DO UPDATE SET role = 'MODERATOR'
                        WHERE community_members.role = 'MEMBER'""")
                .params(slug, username).update();
        jdbc.sql("UPDATE communities SET member_count = (SELECT count(*) FROM community_members WHERE community_id = communities.id) WHERE slug = ?")
                .param(slug).update();
    }

    private long post(String slug, String titlePrefix) {
        return jdbc.sql("SELECT p.id FROM posts p JOIN communities c ON c.id = p.community_id WHERE c.slug = ? AND p.title LIKE ? ORDER BY p.id LIMIT 1")
                .params(slug, titlePrefix + "%").query(Long.class).single();
    }

    private long comment(long postId, String author, String body) {
        return comments.create(postId, require(author), new CreateCommentRequest(body, null)).id();
    }

    /** A real report row, then its timestamp is moved back a little so response times look like a queue, not a script. */
    private void report(long postId, long commentId, String reporter, String reason, String details, int minutesAgo) {
        moderation.report(require(reporter), new ReportRequest("COMMENT", postId, commentId, reason, details));
        jdbc.sql("""
                        UPDATE reports SET created_at = now() - make_interval(mins => ?)
                        WHERE id = (SELECT max(r.id) FROM reports r JOIN users u ON u.id = r.reporter_id WHERE u.username = ? AND r.comment_id = ?)""")
                .params(minutesAgo, reporter, commentId).update();
    }

    private long remove(long commentId, String moderator, String reason) {
        moderation.commentAction(commentId, require(moderator), Action.REMOVE_COMMENT, reason);
        return lastAction(commentId);
    }

    private void approve(long commentId, String moderator) {
        moderation.commentAction(commentId, require(moderator), Action.APPROVE_COMMENT, "No rule broken.");
    }

    private void appeal(long actionId, String author, String message, String moderator, boolean accept, String response) {
        var a = moderation.submitAppeal(actionId, require(author), message);
        moderation.resolveAppeal(a.id(), require(moderator), accept, response);
    }

    private long lastAction(long commentId) {
        return jdbc.sql("SELECT max(id) FROM moderation_actions WHERE comment_id = ?").param(commentId).query(Long.class).single();
    }

    // ---------------------------------------------------------------- collections

    private void collections() {
        AuthUser mira = require("mira_k");
        var reading = collections.create(mira, MARKER_COLLECTION, "Threads I send to new teammates. Demo collection.");
        collections.update(mira, reading.id(), new UpdateCollection(null, null, true));
        add(mira, reading.id(), post("programming", "Keyset pagination cut our feed endpoint"),
                "The cursor-per-sort idea is the part people miss.", List.of("performance", "databases"));
        add(mira, reading.id(), post("programming", "After a year maintaining services in both Rust and Go"),
                "Balanced take; good for the language debate every quarter.", List.of("languages"));
        add(mira, reading.id(), post("technology", "What has AI-assisted coding actually changed"), "", List.of("ai", "teams"));

        AuthUser fern = require("first_job_fern");
        var start = collections.create(fern, "Starting out in tech", "Advice that helped me in my first year. Demo collection.");
        collections.update(fern, start.id(), new UpdateCollection(null, null, true));
        add(fern, start.id(), post("careers", "Six months into my first job and I still feel slow"), "Re-read this on bad weeks.", List.of("first-job"));
        add(fern, start.id(), post("cybersecurity", "The beginner roadmap I wish someone had given me"), "", List.of("learning"));
        add(fern, start.id(), post("careers", "I've run 150 data science interviews"), "Interview prep.", List.of("interviews"));
    }

    private void add(AuthUser user, long collectionId, long postId, String note, List<String> tags) {
        collections.add(user, collectionId, new AddItem("POST", postId, null, note, tags));
    }

    // ---------------------------------------------------------------- funding (demo provider only)

    private void fundingPool() {
        if (!"instant".equals(funding.status().checkout())) {
            // Stripe (even test mode) takes real checkouts; demo contributions are never invented there (D-034).
            log.info("Demo funding pool not seeded: the payment provider isn't the demo provider");
            return;
        }
        try {
            AuthUser mira = require("mira_k");
            funding.setPolicy("programming", mira, new PolicyRequest("ACTIVITY", 50));
            var pool = funding.createPool("programming", mira, new CreatePoolRequest(
                    "Moderator tooling, this quarter (demo)",
                    "Demo pool: covers the moderators' time on the weekly queue and the automod rules. No real money moves in this demo.",
                    "USD", 50_000L, null, Instant.now().plus(Duration.ofDays(45))));
            Map<String, long[]> gifts = Map.of(
                    "dev_ostrander", new long[]{2_500, 1}, "quant_priya", new long[]{5_000, 0}, "first_job_fern", new long[]{1_000, 1},
                    "lena_lab", new long[]{1_500, 0}, "tunde_builds", new long[]{3_000, 0});
            gifts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                    funding.contribute(pool.id(), require(e.getKey()), new ContributeRequest(e.getValue()[0], false, e.getValue()[1] == 1)));
        } catch (RuntimeException e) {
            // Funding switched off (FUNDING_ENABLED=false) or a non-demo provider: skip, the rest of the demo stands.
            log.info("Demo funding pool not seeded: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Optional<AuthUser> user(String username) {
        return jdbc.sql("SELECT id, username, role FROM users WHERE username = ?").param(username)
                .query((rs, i) -> new AuthUser(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    private AuthUser require(String username) {
        return user(username).orElseThrow(() -> new IllegalStateException("Demo user missing: " + username));
    }
}
