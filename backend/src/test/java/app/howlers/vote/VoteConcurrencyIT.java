package app.howlers.vote;

import static org.assertj.core.api.Assertions.assertThat;

import app.howlers.auth.AuthService;
import app.howlers.auth.dto.AuthDtos.RegisterRequest;
import app.howlers.common.security.AuthUser;
import app.howlers.community.CommunityService;
import app.howlers.community.dto.CommunityDtos.CreateCommunityRequest;
import app.howlers.post.PostService;
import app.howlers.post.dto.PostDtos.CreatePostRequest;
import app.howlers.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import app.howlers.support.TestIds;

/** Votes from many users at once, and many clicks from one user at once, must never corrupt counters. */
class VoteConcurrencyIT extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired CommunityService communities;
    @Autowired PostService posts;
    @Autowired VoteService votes;
    @Autowired JdbcClient jdbc;

    private AuthUser user(String name) {
        var me = auth.register(new RegisterRequest(name, name + "@test.local", "password1", null), "test", "127.0.0.1")
                .response().user();
        return new AuthUser(me.id(), me.username(), me.role());
    }

    @Test
    void concurrentVotesKeepCountersConsistent() throws Exception {
        String sfx = TestIds.suffix();
        AuthUser author = user("author" + sfx);
        communities.create(author, new CreateCommunityRequest("vc" + sfx, "Votes", "", null, "PUBLIC", List.of(), List.of()));
        long postId = posts.create(author, new CreatePostRequest("vc" + sfx, "TEXT", "Race me", "<p>x</p>", null, null, null, null, null));

        List<AuthUser> voters = new ArrayList<>();
        for (int i = 0; i < 16; i++) voters.add(user("v" + i + "_" + sfx));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        // 12 upvote, 4 downvote; plus one voter hammering toggles concurrently.
        for (int i = 0; i < voters.size(); i++) {
            AuthUser v = voters.get(i);
            int value = i < 12 ? 1 : -1;
            futures.add(pool.submit(() -> { start.await(); return votes.votePost(postId, v, value); }));
        }
        AuthUser hammer = voters.getFirst();
        for (int i = 0; i < 10; i++) {
            int value = i % 2 == 0 ? -1 : 1; // ends on +1 eventually but order is racy
            futures.add(pool.submit(() -> { start.await(); return votes.votePost(postId, hammer, value); }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        record Counters(int up, int down, int score) {}
        Counters c = jdbc.sql("SELECT upvotes, downvotes, score FROM posts WHERE id = ?").param(postId)
                .query((rs, i) -> new Counters(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
        Counters truth = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE value = 1), count(*) FILTER (WHERE value = -1), coalesce(sum(value), 0)
                        FROM post_votes WHERE post_id = ?""").param(postId)
                .query((rs, i) -> new Counters(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();

        assertThat(c).isEqualTo(truth);
        assertThat(c.up() + c.down()).isEqualTo(16); // one row per voter, never duplicated
        int karma = jdbc.sql("SELECT post_karma FROM users WHERE id = ?").param(author.id()).query(Integer.class).single();
        assertThat(karma).isEqualTo(truth.score());
    }
}
