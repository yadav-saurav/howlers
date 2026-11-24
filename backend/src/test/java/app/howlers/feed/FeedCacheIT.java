package app.howlers.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.howlers.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The first-page feed cache stores only rank order; every per-viewer field must still be computed per viewer (D-025). */
@AutoConfigureMockMvc
class FeedCacheIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FeedCache cache;

    @AfterEach
    void restore() {
        cache.useStoreForTests(null, false);
    }

    private static String sfx() {
        return Long.toString(System.nanoTime() % 10_000_000);
    }

    private String register(String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + name + "@fc.local\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return "Bearer " + json.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String auth) {
        return auth == null ? b : b.header("Authorization", auth);
    }

    private long createPost(String auth, String slug, String title) throws Exception {
        MvcResult r = mvc.perform(as(post("/api/v1/posts"), auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communitySlug\":\"" + slug + "\",\"type\":\"TEXT\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    /** id -> post JSON, in feed order. */
    private Map<Long, JsonNode> feed(String slug, String auth) throws Exception {
        MvcResult r = mvc.perform(as(get("/api/v1/communities/" + slug + "/posts?sort=new"), auth)).andExpect(status().isOk()).andReturn();
        Map<Long, JsonNode> out = new java.util.LinkedHashMap<>();
        json.readTree(r.getResponse().getContentAsString()).get("items").forEach(p -> out.put(p.get("id").asLong(), p));
        return out;
    }

    @Test
    void warmCacheStillComputesVotesSavesHiddenAndModeratorFlagsPerViewer() throws Exception {
        String s = sfx();
        String owner = register("fco" + s), viewer = register("fcv" + s), other = register("fcx" + s);
        String slug = "fc" + s;
        mvc.perform(as(post("/api/v1/communities"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Feed cache\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        long p1 = createPost(owner, slug, "first"), p2 = createPost(owner, slug, "second"), p3 = createPost(owner, slug, "third");

        // The viewer upvotes p2, saves p3 and hides p1 before the cache is warmed by an anonymous request.
        mvc.perform(as(post("/api/v1/posts/" + p2 + "/vote"), viewer).contentType(MediaType.APPLICATION_JSON).content("{\"value\":1}"))
                .andExpect(status().isOk());
        mvc.perform(as(put("/api/v1/posts/" + p3 + "/save"), viewer)).andExpect(status().is2xxSuccessful());
        mvc.perform(as(put("/api/v1/posts/" + p1 + "/hide"), viewer)).andExpect(status().is2xxSuccessful());

        FeedCache.MemoryStore store = new FeedCache.MemoryStore();
        List<String> reads = new ArrayList<>();
        cache.useStoreForTests(new FeedCache.Store() {
            @Override
            public String get(String key) {
                reads.add(key);
                return store.get(key);
            }

            @Override
            public void set(String key, String value, Duration ttl) {
                store.set(key, value, ttl);
            }
        }, true);

        Map<Long, JsonNode> anon = feed(slug, null);
        assertThat(anon.keySet()).containsExactly(p3, p2, p1);
        assertThat(store.map).hasSize(1);
        String cached = store.map.values().iterator().next();
        // Only ids and the cursor are stored: no titles, votes or viewer flags.
        assertThat(cached).doesNotContain("third").doesNotContain("myVote").doesNotContain("saved");
        anon.values().forEach(p -> {
            assertThat(p.get("myVote").asInt()).isZero();
            assertThat(p.get("saved").asBoolean()).isFalse();
            assertThat(p.get("viewer").get("canModerate").asBoolean()).isFalse();
        });

        // Same cache entry, different viewers: each gets their own fields.
        Map<Long, JsonNode> mine = feed(slug, viewer);
        assertThat(mine.keySet()).containsExactly(p3, p2); // p1 is hidden for this viewer only
        assertThat(mine.get(p2).get("myVote").asInt()).isEqualTo(1);
        assertThat(mine.get(p2).get("score").asInt()).isEqualTo(anon.get(p2).get("score").asInt());
        assertThat(mine.get(p3).get("saved").asBoolean()).isTrue();
        assertThat(mine.get(p3).get("myVote").asInt()).isZero();

        Map<Long, JsonNode> theirs = feed(slug, other);
        assertThat(theirs.keySet()).containsExactly(p3, p2, p1);
        theirs.values().forEach(p -> {
            assertThat(p.get("myVote").asInt()).isZero();
            assertThat(p.get("saved").asBoolean()).isFalse();
        });

        Map<Long, JsonNode> owners = feed(slug, owner);
        owners.values().forEach(p -> assertThat(p.get("viewer").get("canModerate").asBoolean()).isTrue());
        assertThat(store.map).hasSize(1); // all four requests shared one entry
        assertThat(reads).hasSize(4);

        // The cache really serves: a new post doesn't appear until the entry expires (the tolerated staleness)...
        long p4 = createPost(owner, slug, "fourth");
        assertThat(feed(slug, null).keySet()).doesNotContain(p4);
        // ...but a deleted post disappears at once, because cached ids are re-checked on every read.
        mvc.perform(as(delete("/api/v1/posts/" + p3), owner)).andExpect(status().is2xxSuccessful());
        assertThat(feed(slug, null).keySet()).containsExactly(p2, p1);
        assertThat(feed(slug, viewer).keySet()).containsExactly(p2);

        // Expired entry: recomputed with the new post.
        store.map.clear();
        assertThat(feed(slug, null).keySet()).containsExactly(p4, p2, p1);
    }

    @Test
    void redisFailureFallsBackToUncachedFeeds() throws Exception {
        String s = sfx();
        String owner = register("fcr" + s);
        String slug = "fcr" + s;
        mvc.perform(as(post("/api/v1/communities"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"name\":\"Feed cache down\",\"visibility\":\"PUBLIC\"}")).andExpect(status().isCreated());
        long p1 = createPost(owner, slug, "only");
        Map<String, Integer> calls = new HashMap<>();
        cache.useStoreForTests(new FeedCache.Store() {
            @Override
            public String get(String key) {
                calls.merge("get", 1, Integer::sum);
                throw new IllegalStateException("redis down");
            }

            @Override
            public void set(String key, String value, Duration ttl) {
                calls.merge("set", 1, Integer::sum);
                throw new IllegalStateException("redis down");
            }
        }, true);
        assertThat(feed(slug, null).keySet()).containsExactly(p1);
        assertThat(feed(slug, owner).keySet()).containsExactly(p1);
        // After the first failure the cache steps aside instead of paying the Redis timeout on every request.
        assertThat(calls.get("get")).isEqualTo(1);
        assertThat(calls.get("set")).isNull();
    }

    @Test
    void codecRoundTrips() {
        FeedCache.Entry e = new FeedCache.Entry(List.of(5L, 3L, 1L), "abc_-");
        assertThat(FeedCache.decode(FeedCache.encode(e))).isEqualTo(e);
        FeedCache.Entry last = new FeedCache.Entry(List.of(), null);
        assertThat(FeedCache.decode(FeedCache.encode(last))).isEqualTo(last);
    }
}
