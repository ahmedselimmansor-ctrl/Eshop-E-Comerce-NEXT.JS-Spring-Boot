package com.eshop.search.service;

import com.eshop.common.Uuid7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Records what shoppers search for.
 *
 * <p>Two things depend on this: the "popular searches" list on the empty search screen, and the
 * zero-result report that tells merchandisers what the catalog is missing — "خلاط" returning nothing
 * two hundred times a week is a buying decision, not a search bug.
 *
 * <p>Every method is {@code @Async} and swallows its own failures. Search analytics must never slow
 * down or break a search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchAnalyticsService {

    private final JdbcTemplate jdbc;

    /**
     * @param resultCount stored so zero-result queries can be reported separately
     */
    @Async
    @Transactional
    public void recordQuery(String query, String locale, long resultCount) {
        if (query == null || query.isBlank()) {
            return;
        }
        try {
            jdbc.update("""
                            INSERT INTO search_queries (id, query, normalized_query, locale, results_count, created_at)
                            VALUES (?, ?, ?, ?, ?, now())
                            """,
                    Uuid7.generate(),
                    truncate(query, 200),
                    normalize(query),
                    locale,
                    (int) Math.min(resultCount, Integer.MAX_VALUE));

        } catch (RuntimeException e) {
            log.debug("search_analytics_write_failed error={}", e.getMessage());
        }
    }

    @Async
    @Transactional
    public void recordClick(String query, UUID productId) {
        if (query == null || query.isBlank() || productId == null) {
            return;
        }
        try {
            // Attaches the click to this shopper's most recent matching query rather than inserting
            // a new row, so click-through rate per query stays computable.
            jdbc.update("""
                            UPDATE search_queries
                               SET clicked_product_id = ?
                             WHERE id = (
                                 SELECT id FROM search_queries
                                  WHERE normalized_query = ? AND clicked_product_id IS NULL
                                  ORDER BY created_at DESC
                                  LIMIT 1)
                            """,
                    productId, normalize(query));

        } catch (RuntimeException e) {
            log.debug("search_click_write_failed error={}", e.getMessage());
        }
    }

    /**
     * Trending terms from the last week.
     *
     * <p>Only queries that returned something are offered as suggestions — proposing a term the
     * catalog cannot answer wastes the shopper's time.
     */
    @Transactional(readOnly = true)
    public List<String> popularQueries(int limit) {
        try {
            return jdbc.queryForList("""
                            SELECT normalized_query
                              FROM search_queries
                             WHERE created_at > now() - INTERVAL '7 days'
                               AND results_count > 0
                               AND length(normalized_query) >= 2
                             GROUP BY normalized_query
                            HAVING COUNT(*) >= 3
                             ORDER BY COUNT(*) DESC
                             LIMIT ?
                            """, String.class, limit);

        } catch (RuntimeException e) {
            log.debug("popular_queries_failed error={}", e.getMessage());
            return List.of();
        }
    }

    /** Searches that found nothing — the merchandising gap report. */
    @Transactional(readOnly = true)
    public List<ZeroResultQuery> zeroResultQueries(int days, int limit) {
        try {
            return jdbc.query("""
                            SELECT normalized_query AS query, COUNT(*) AS searches
                              FROM search_queries
                             WHERE created_at > now() - make_interval(days => ?)
                               AND results_count = 0
                               AND length(normalized_query) >= 2
                             GROUP BY normalized_query
                             ORDER BY COUNT(*) DESC
                             LIMIT ?
                            """,
                    (rs, rowNum) -> new ZeroResultQuery(rs.getString("query"), rs.getLong("searches")),
                    days, limit);

        } catch (RuntimeException e) {
            log.debug("zero_result_queries_failed error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Collapses the spelling variations that would otherwise fragment the counts: case, extra
     * whitespace, Arabic diacritics, and the interchangeable alef/yaa/taa-marbuta forms.
     */
    private static String normalize(String query) {
        String value = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        value = Normalizer.normalize(value, Normalizer.Form.NFKD);
        value = value.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");

        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(switch (c) {
                case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
                case 'ى', 'ئ' -> 'ي';
                case 'ة' -> 'ه';
                case 'ؤ' -> 'و';
                default -> c;
            });
        }
        return truncate(out.toString(), 200);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record ZeroResultQuery(String query, long searches) {
    }
}
