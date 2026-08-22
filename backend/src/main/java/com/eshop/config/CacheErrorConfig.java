package com.eshop.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the cache a genuine optimisation rather than a hard dependency.
 *
 * <p>Without this, a Redis outage turns every {@code @Cacheable} method into a 500 - the site goes
 * down because a *cache* is unavailable. Here a failed get, put, or evict is logged and swallowed,
 * so the call proceeds to the database. The trade-off is deliberate: an evict that fails leaves a
 * stale entry until its TTL expires, which is why every cache in {@link RedisConfig} has a bounded
 * TTL and none of them exceed fifteen minutes.
 */
@Slf4j
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("cache_get_failed cache={} key={} error={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("cache_put_failed cache={} key={} error={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // Worth more attention than a read miss: the entry now serves stale data until TTL.
                log.error("cache_evict_failed cache={} key={} error={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.error("cache_clear_failed cache={} error={}", cache.getName(), exception.getMessage());
            }
        };
    }
}
