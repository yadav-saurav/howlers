package app.howlers.feed;

import app.howlers.common.metrics.HowlersMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-lived Redis cache of first-page feed <b>id lists</b> (D-025). Only the anonymous rank order is cached: the
 * ids plus the next cursor, never a hydrated post. Every request then re-filters those ids for the viewer (hidden,
 * blocked, muted, deleted, removed) and hydrates them fresh, so per-viewer fields ({@code myVote}, {@code saved},
 * {@code viewer}) and counts can't leak or go stale through the cache; only the order can be up to {@code ttl} old.
 * <p>
 * If Redis is unreachable the cache steps aside for 30 s at a time and feeds are computed directly, so an outage
 * costs latency, never errors.
 */
@Component
public class FeedCache {
    private static final Logger log = LoggerFactory.getLogger(FeedCache.class);
    private static final String PREFIX = "feed:v1:";
    private static final long RETRY_AFTER_MS = 30_000;

    /** Minimal key/value contract so tests can plug in an in-memory store. */
    interface Store {
        String get(String key);

        void set(String key, String value, Duration ttl);
    }

    record Entry(List<Long> ids, String nextCursor) {}

    private final HowlersMetrics metrics;
    private final Duration ttl;
    private final Store redisStore;
    private volatile Store store;
    private volatile boolean enabled;
    private volatile long downUntil;

    public FeedCache(StringRedisTemplate redis, HowlersMetrics metrics,
                     @Value("${howlers.feed-cache.enabled:true}") boolean enabled,
                     @Value("${howlers.feed-cache.ttl:PT30S}") Duration ttl) {
        this.metrics = metrics;
        this.enabled = enabled;
        this.ttl = ttl;
        this.redisStore = new Store() {
            @Override
            public String get(String key) {
                return redis.opsForValue().get(key);
            }

            @Override
            public void set(String key, String value, Duration t) {
                redis.opsForValue().set(key, value, t);
            }
        };
        this.store = redisStore;
    }

    /** Returns the cached entry for {@code key}, or computes, stores and returns it. */
    Entry get(String key, Supplier<Entry> compute) {
        if (!enabled) return compute.get();
        String full = PREFIX + key;
        Optional<Entry> hit = read(full);
        if (hit.isPresent()) {
            metrics.feedCache(true);
            return hit.get();
        }
        metrics.feedCache(false);
        Entry fresh = compute.get();
        write(full, fresh);
        return fresh;
    }

    private Optional<Entry> read(String key) {
        if (System.currentTimeMillis() < downUntil) return Optional.empty();
        try {
            String v = store.get(key);
            recovered();
            return Optional.ofNullable(v).map(FeedCache::decode);
        } catch (RuntimeException e) {
            failed(e);
            return Optional.empty();
        }
    }

    private void write(String key, Entry e) {
        if (System.currentTimeMillis() < downUntil) return;
        try {
            store.set(key, encode(e), ttl);
        } catch (RuntimeException ex) {
            failed(ex);
        }
    }

    private void failed(RuntimeException e) {
        if (downUntil == 0) log.warn("Feed cache: Redis unavailable, serving feeds uncached ({})", e.getClass().getSimpleName());
        downUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
    }

    private void recovered() {
        if (downUntil != 0) {
            downUntil = 0;
            log.info("Feed cache: Redis reachable again");
        }
    }

    /** "cursor|1,2,3" (cursor is base64url, so it never contains '|'; an empty cursor means last page). */
    static String encode(Entry e) {
        StringBuilder sb = new StringBuilder(e.nextCursor() == null ? "" : e.nextCursor()).append('|');
        for (int i = 0; i < e.ids().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(e.ids().get(i));
        }
        return sb.toString();
    }

    static Entry decode(String v) {
        int bar = v.indexOf('|');
        String cursor = bar <= 0 ? null : v.substring(0, bar);
        String rest = v.substring(bar + 1);
        List<Long> ids = new ArrayList<>();
        if (!rest.isEmpty()) for (String s : rest.split(",")) ids.add(Long.parseLong(s));
        return new Entry(List.copyOf(ids), cursor);
    }

    // ---------------------------------------------------------------- test hooks (package-private)

    /** {@code s == null} restores the Redis store. */
    void useStoreForTests(Store s, boolean on) {
        this.store = s == null ? redisStore : s;
        this.enabled = on;
        this.downUntil = 0;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** In-memory store for tests (ignores the TTL). */
    static final class MemoryStore implements Store {
        final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            map.put(key, value);
        }
    }
}
