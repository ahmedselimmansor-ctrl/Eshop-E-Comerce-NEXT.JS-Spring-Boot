package com.eshop;

import com.eshop.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Eshop - modular monolith.
 *
 * <p>One deployable, one database, and one transaction boundary around the operations that need
 * it (reserving stock while creating an order). Domains under {@code com.eshop.*} own their tables
 * and talk to each other only through service interfaces; {@code ArchitectureTest} fails the build
 * if a package reaches into another domain's repositories, which keeps the seams intact for the day
 * a domain genuinely needs to be extracted.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableJpaAuditing
@EnableTransactionManagement
public class EshopApplication {

    public static void main(String[] args) {
        SpringApplication.run(EshopApplication.class, args);
    }
}
