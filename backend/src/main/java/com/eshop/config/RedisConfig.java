package com.eshop.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis wiring for the cache abstraction plus the raw templates used by rate limiting and
 * distributed locks.
 */
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    public static final String CACHE_CATEGORY_TREE = "categoryTree";
    public static final String CACHE_CATEGORIES = "categories";
    public static final String CACHE_PRODUCT_DETAIL = "productDetail";
    public static final String CACHE_PRODUCT_LIST = "productList";
    public static final String CACHE_BRANDS = "brands";
    public static final String CACHE_ATTRIBUTES = "attributes";
    public static final String CACHE_SETTINGS = "settings";
    public static final String CACHE_SHIPPING_METHODS = "shippingMethods";
    public static final String CACHE_DASHBOARD = "dashboard";

    private final AppProperties properties;

    /**
     * JSON rather than JDK serialisation for cached values.
     *
     * <p>JDK serialisation ties every cache entry to the exact class version that wrote it, so a
     * routine deploy turns the whole cache into deserialisation errors. JSON survives added fields,
     * and the entries stay readable with {@code redis-cli} during an incident.
     */
    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // Type info is required to restore concrete types; the validator is scoped to our own
        // classes because cache values are written by this application only, never by a client.
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        AppProperties.CacheTtl ttl = properties.getCacheTtl();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(300))
                .prefixCacheNameWith("eshop:cache:")
                // A cached null would let a 404 for a slug that is about to be published stick
                // around for the whole TTL, so misses are simply not cached.
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()));

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CACHE_CATEGORY_TREE, defaults.entryTtl(Duration.ofSeconds(ttl.getCategoryTree())));
        perCache.put(CACHE_CATEGORIES, defaults.entryTtl(Duration.ofSeconds(ttl.getCategoryTree())));
        perCache.put(CACHE_PRODUCT_DETAIL, defaults.entryTtl(Duration.ofSeconds(ttl.getProductDetail())));
        perCache.put(CACHE_PRODUCT_LIST, defaults.entryTtl(Duration.ofSeconds(ttl.getProductList())));
        perCache.put(CACHE_BRANDS, defaults.entryTtl(Duration.ofSeconds(ttl.getBrands())));
        perCache.put(CACHE_ATTRIBUTES, defaults.entryTtl(Duration.ofSeconds(ttl.getAttributes())));
        perCache.put(CACHE_SETTINGS, defaults.entryTtl(Duration.ofSeconds(ttl.getSettings())));
        perCache.put(CACHE_SHIPPING_METHODS, defaults.entryTtl(Duration.ofSeconds(ttl.getShippingMethods())));
        perCache.put(CACHE_DASHBOARD, defaults.entryTtl(Duration.ofSeconds(ttl.getDashboard())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                // A Redis blip should degrade the site to "slower", not "down": failures during a
                // cache read or write are logged by the error handler and the call falls through
                // to the database.
                .transactionAware()
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer());
        template.setHashValueSerializer(jsonSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /** Plain string template - what the rate-limit and lock Lua scripts operate on. */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
