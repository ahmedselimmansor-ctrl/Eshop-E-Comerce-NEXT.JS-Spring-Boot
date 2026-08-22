package com.eshop.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Base class for tests that run against a real Postgres and Redis.
 *
 * <p>The dependencies are real rather than stubbed because the defects this suite exists to catch
 * are exactly the ones a stub cannot see: {@code jsonb} rejecting a malformed value, a lazy
 * association that resolves only while a session is open, a migration that does not apply. A mocked
 * repository returns whatever it was told to and proves nothing about any of them.
 *
 * <p>The services are supplied from outside rather than started by Testcontainers. That is the same
 * shape CI already uses — service containers published on localhost — and it means the suite does
 * not need a Docker socket, which matters because the build itself runs inside a container.
 *
 * <p>Locally: {@code docker compose up -d postgres redis}. Override the defaults with
 * {@code TEST_DB_URL}, {@code TEST_DB_USER}, {@code TEST_DB_PASSWORD}, {@code TEST_REDIS_HOST}, and
 * {@code TEST_REDIS_PORT} if your services live elsewhere.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    private static final String HOST = env("TEST_DB_HOST", "localhost");
    private static final String PORT = env("TEST_DB_PORT", "5432");
    private static final String USER = env("TEST_DB_USER", "eshop");
    private static final String PASSWORD = env("TEST_DB_PASSWORD", "eshop");

    /**
     * A database of its own, so a test run never truncates a developer's working data — {@link
     * TestFixtures#reset()} would otherwise empty whatever database happened to be configured.
     */
    private static final String DATABASE = env("TEST_DB_NAME", "eshop_test");

    static {
        createDatabaseIfAbsent();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // stringtype=unspecified, exactly as application.yml sets it. It is not incidental: several
        // columns are Postgres `inet` (orders.ip, refresh_tokens.ip, audit_logs.ip) and the entities
        // map them as String, so without it the driver binds varchar and Postgres refuses the insert.
        // A test URL missing this parameter tests a configuration the application never runs under.
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE
                        + "?stringtype=unspecified");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);

        registry.add("spring.data.redis.host", () -> env("TEST_REDIS_HOST", "localhost"));
        registry.add("spring.data.redis.port", () -> env("TEST_REDIS_PORT", "6379"));
    }

    /**
     * Flyway migrates the schema but cannot create the database itself, so this connects to the
     * maintenance database first and creates it when missing.
     */
    private static void createDatabaseIfAbsent() {
        String maintenanceUrl = "jdbc:postgresql://" + HOST + ":" + PORT + "/postgres";

        try (Connection connection = DriverManager.getConnection(maintenanceUrl, USER, PASSWORD);
             Statement statement = connection.createStatement()) {

            try (ResultSet existing = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
                if (existing.next()) {
                    return;
                }
            }
            statement.execute("CREATE DATABASE " + DATABASE);

        } catch (Exception e) {
            throw new IllegalStateException("""
                    Could not reach Postgres at %s:%s as user '%s'.

                    The integration tests need a running Postgres and Redis. Start them with:
                        docker compose up -d postgres redis

                    Or point the suite elsewhere with TEST_DB_HOST / TEST_DB_PORT / TEST_DB_USER /
                    TEST_DB_PASSWORD.""".formatted(HOST, PORT, USER), e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected TestFixtures fixtures;

    @BeforeEach
    void resetFixtures() {
        fixtures.reset();
    }
}
