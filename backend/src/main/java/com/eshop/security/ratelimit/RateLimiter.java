package com.eshop.security.ratelimit;

import com.eshop.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token-bucket rate limiting evaluated inside Redis.
 *
 * <p>The whole decision - refill, test, consume, compute reset - happens in one Lua script, which
 * Redis runs atomically. A read-modify-write from the application would let concurrent requests
 * across pods each observe the same remaining count and all pass, which is exactly the burst the
 * limiter exists to stop.
 *
 * <p>A token bucket rather than a fixed window: a fixed window lets a client spend its full quota in
 * the last instant of one window and again in the first instant of the next, producing a burst of
 * twice the intended rate at the boundary. The bucket refills continuously, so the rate holds
 * everywhere while still tolerating short legitimate bursts up to the bucket's capacity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String KEY_PREFIX = "eshop:rl:";

    /**
     * KEYS[1] bucket key
     * ARGV[1] capacity · ARGV[2] window seconds · ARGV[3] now (ms) · ARGV[4] requested tokens
     * Returns { allowed, remaining, resetMillis, retryAfterSeconds }
     */
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local capacity   = tonumber(ARGV[1])
            local window     = tonumber(ARGV[2])
            local now        = tonumber(ARGV[3])
            local requested  = tonumber(ARGV[4])
            local refillRate = capacity / (window * 1000.0)   -- tokens per millisecond

            local bucket    = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens    = tonumber(bucket[1])
            local lastSeen  = tonumber(bucket[2])

            if tokens == nil then
                tokens   = capacity
                lastSeen = now
            else
                local elapsed = math.max(0, now - lastSeen)
                tokens   = math.min(capacity, tokens + elapsed * refillRate)
                lastSeen = now
            end

            local allowed = 0
            if tokens >= requested then
                tokens  = tokens - requested
                allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', lastSeen)
            -- Expire an idle bucket after a full refill period; keeping it would leak one key per
            -- IP forever.
            redis.call('PEXPIRE', KEYS[1], math.ceil(window * 1000 * 2))

            local missing    = math.max(0, requested - tokens)
            local retryMs    = 0
            if allowed == 0 then
                retryMs = math.ceil(missing / refillRate)
            end
            local resetMs = math.ceil((capacity - tokens) / refillRate)

            return { allowed, math.floor(tokens), now + resetMs, math.ceil(retryMs / 1000) }
            """, List.class);

    private final StringRedisTemplate redis;
    private final AppProperties properties;

    /**
     * Consumes one token from the named bucket.
     *
     * <p>If Redis is unreachable the request is <b>allowed</b>. A limiter that fails closed converts
     * a cache outage into a full site outage; the abuse it would have blocked is bounded by how long
     * Redis stays down, and Cloud Armor is still enforcing a coarser limit at the edge.
     */
    public Decision tryConsume(String tier, String identity) {
        if (!properties.getRateLimit().isEnabled()) {
            return Decision.allowed(Long.MAX_VALUE, Long.MAX_VALUE, 0);
        }

        AppProperties.RateLimit.Tier config = properties.getRateLimit().tier(tier);
        String key = KEY_PREFIX + tier + ":" + identity;
        long now = System.currentTimeMillis();

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(config.getCapacity()),
                    String.valueOf(config.getWindowSeconds()),
                    String.valueOf(now),
                    "1");

            if (result == null || result.size() < 4) {
                return Decision.allowed(config.getCapacity(), config.getCapacity(), 0);
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            long resetEpochMs = result.get(2);
            long retryAfterSeconds = Math.max(1, result.get(3));

            return new Decision(allowed, config.getCapacity(), remaining, resetEpochMs,
                    allowed ? 0 : retryAfterSeconds);

        } catch (RuntimeException e) {
            log.warn("rate_limit_unavailable tier={} identity={} error={} - failing open",
                    tier, identity, e.getMessage());
            return Decision.allowed(config.getCapacity(), config.getCapacity(), 0);
        }
    }

    /** Clears a bucket - used after a successful login so a legitimate user is not left throttled. */
    public void reset(String tier, String identity) {
        try {
            redis.delete(KEY_PREFIX + tier + ":" + identity);
        } catch (RuntimeException e) {
            log.debug("rate_limit_reset_failed tier={} error={}", tier, e.getMessage());
        }
    }

    /**
     * @param allowed           whether the caller may proceed
     * @param limit             bucket capacity, surfaced as {@code X-RateLimit-Limit}
     * @param remaining         tokens left, surfaced as {@code X-RateLimit-Remaining}
     * @param resetEpochMs      when the bucket is full again, surfaced as {@code X-RateLimit-Reset}
     * @param retryAfterSeconds how long to wait, surfaced as {@code Retry-After} on a 429
     */
    public record Decision(boolean allowed, long limit, long remaining, long resetEpochMs, long retryAfterSeconds) {

        static Decision allowed(long limit, long remaining, long retryAfterSeconds) {
            return new Decision(true, limit, remaining, System.currentTimeMillis(), retryAfterSeconds);
        }
    }
}
