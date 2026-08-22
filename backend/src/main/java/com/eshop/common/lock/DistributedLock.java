package com.eshop.common.lock;

import com.eshop.common.util.Hashes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Best-effort mutual exclusion across pods, backed by a single Redis instance.
 *
 * <p>Used for work that is wasteful — not catastrophic — to run twice: the outbox relay, the
 * reservation sweeper, nightly cleanup, and search reindexing. Every one of those is idempotent by
 * construction, so a lock lost to a Redis failover costs duplicated effort, never corrupted data.
 *
 * <p><b>What this is not.</b> This is not a correctness boundary. Anything that must happen exactly
 * once — decrementing stock, capturing a payment — relies on the database instead: {@code SELECT
 * … FOR UPDATE}, a unique constraint, or an optimistic-lock version column. A single-node Redis lock
 * cannot survive a failover window, and pretending otherwise is how oversold inventory happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    private static final String KEY_PREFIX = "eshop:lock:";

    /**
     * Releases only if the stored token still matches this holder. Without the comparison a slow
     * holder whose lease already expired would delete a lock a different pod now owns.
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    /** Extends the lease, again only for the current holder. */
    private static final DefaultRedisScript<Long> EXTEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * Runs {@code action} if the lock is free, and returns {@code false} without running it if
     * another pod holds it. Never blocks.
     *
     * @param name  logical lock name, e.g. {@code outbox-relay}
     * @param lease how long the lock survives if this pod dies mid-run; must exceed the expected
     *              runtime or a second pod will start the job while the first is still working
     */
    public boolean runIfAcquired(String name, Duration lease, Runnable action) {
        String key = KEY_PREFIX + name;
        String token = Hashes.randomToken(16);

        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("lock_busy name={}", name);
            return false;
        }

        try {
            action.run();
            return true;
        } finally {
            release(key, token, name);
        }
    }

    /** Same contract as {@link #runIfAcquired}, for work that produces a value. */
    public <T> java.util.Optional<T> callIfAcquired(String name, Duration lease, Supplier<T> action) {
        String key = KEY_PREFIX + name;
        String token = Hashes.randomToken(16);

        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
        if (!Boolean.TRUE.equals(acquired)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(action.get());
        } finally {
            release(key, token, name);
        }
    }

    /** Pushes the expiry out for a long-running job that is still making progress. */
    public boolean extend(String name, String token, Duration lease) {
        Long extended = redis.execute(EXTEND_SCRIPT, List.of(KEY_PREFIX + name),
                token, String.valueOf(lease.toMillis()));
        return extended != null && extended == 1L;
    }

    private void release(String key, String token, String name) {
        try {
            Long released = redis.execute(RELEASE_SCRIPT, List.of(key), token);
            if (released == null || released == 0L) {
                // The lease expired while the job was still running — the job took longer than the
                // lease allowed, which means another pod may already have started it.
                log.warn("lock_lease_expired_before_release name={}", name);
            }
        } catch (RuntimeException e) {
            // The lease will expire on its own; failing the job over a release error would be worse.
            log.warn("lock_release_failed name={} error={}", name, e.getMessage());
        }
    }
}
