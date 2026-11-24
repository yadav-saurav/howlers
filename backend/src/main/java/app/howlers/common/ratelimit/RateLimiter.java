package app.howlers.common.ratelimit;

import app.howlers.common.config.AppProperties;
import app.howlers.common.error.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter. Redis-backed so limits hold across instances; if Redis is unreachable it
 * degrades to a per-instance in-memory window rather than failing requests (logged once per outage).
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Map<String, long[]> local = new ConcurrentHashMap<>();
    private volatile boolean redisDown;

    public RateLimiter(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.enabled = props.rateLimit().enabled();
    }

    /** Throws 429 when {@code key} exceeded {@code max} hits within {@code window}. */
    public void check(String key, int max, Duration window) {
        if (!enabled) return;
        long count = increment("rl:" + key, window);
        if (count > max) throw ApiException.rateLimited();
    }

    private long increment(String key, Duration window) {
        try {
            Long c = redis.execute(INCR_WITH_TTL, List.of(key), Long.toString(window.toMillis()));
            if (redisDown) {
                redisDown = false;
                log.info("Rate limiter: Redis reachable again");
            }
            return c == null ? 0 : c;
        } catch (RuntimeException e) {
            if (!redisDown) {
                redisDown = true;
                log.warn("Rate limiter: Redis unavailable, falling back to in-memory windows ({})", e.getClass().getSimpleName());
            }
            long now = System.currentTimeMillis();
            long[] slot = local.compute(key, (k, v) -> (v == null || v[0] <= now) ? new long[]{now + window.toMillis(), 0} : v);
            synchronized (slot) {
                return ++slot[1];
            }
        }
    }
}
