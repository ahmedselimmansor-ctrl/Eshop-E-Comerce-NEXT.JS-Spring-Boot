package com.eshop.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Applies the demo catalog on startup, in development only.
 *
 * <p>Deliberately not a Flyway migration. Migrations run in every environment, and a real store does
 * not want eight fictional products in its catalog - but a developer who has just cloned the
 * repository wants a storefront with something on it, not an empty grid that makes every page look
 * broken.
 *
 * <p>{@code @Profile({"dev", "local"})} is the whole safety mechanism, so it is worth being explicit
 * about what it excludes: {@code prod} and {@code staging} never instantiate this class.
 *
 * <p>The script is idempotent - every statement ends in {@code ON CONFLICT DO NOTHING} - so
 * restarting a seeded environment is a no-op rather than an error.
 */
@Slf4j
@Component
@Profile({"dev", "local"})
@RequiredArgsConstructor
public class DevSeedLoader {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            // ResourceDatabasePopulator rather than JdbcTemplate.execute: the latter hands the whole
            // file to a single Statement, which silently runs only the leading comment block and
            // reports success having inserted nothing. This splits on statement boundaries and
            // strips comments, which is the difference between a seeded database and a confusing
            // "applied, 0 products" line in the log.
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/seed/dev-seed.sql"));
            populator.setCommentPrefixes("--");
            populator.execute(dataSource);

            log.info("dev_seed_applied products={} brands={}",
                    jdbc.queryForObject(
                            "SELECT count(*) FROM products WHERE deleted_at IS NULL", Integer.class),
                    jdbc.queryForObject("SELECT count(*) FROM brands", Integer.class));

        } catch (Exception e) {
            // A failed demo seed must not stop the application: the developer can still work, and an
            // exception here would otherwise be indistinguishable from a real startup failure.
            //
            // The root cause is logged rather than e.getMessage(), which for a script failure is the
            // entire file - hundreds of lines burying the one that matters.
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            log.warn("dev_seed_failed reason={} - continuing without demo data", cause.getMessage());
        }
    }
}
