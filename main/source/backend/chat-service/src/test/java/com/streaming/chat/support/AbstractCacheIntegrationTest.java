package com.streaming.chat.support;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for cache-aside integration tests that need <b>both</b> a real
 * PostgreSQL (system of record) and a real Redis (hot cache).
 *
 * <p>Starts one Postgres and one Redis container per test class and wires their
 * coordinates into the Spring {@code Environment} via {@link DynamicPropertySource}:
 * <ul>
 *   <li>{@code spring.r2dbc.*} — reactive datasource for the repositories, scoped to
 *       the {@code chat} schema via {@code search_path}.</li>
 *   <li>{@code spring.flyway.*} — the blocking JDBC side-channel that runs the chat
 *       migrations (V1..V4) against the container before the tests execute.</li>
 *   <li>{@code spring.data.redis.*} — the reactive Redis hot cache.</li>
 * </ul>
 *
 * <p>Uses the {@code test} profile (see {@code application-test.yml}) which disables
 * the Kafka listener and Eureka so the context starts without external brokers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractCacheIntegrationTest {

    protected static final String REDIS_IMAGE = "redis:7-alpine";
    protected static final String POSTGRES_IMAGE = "postgres:16-alpine";
    protected static final int REDIS_PORT = 6379;

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("streaming_platform")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // R2DBC — reactive path used by the repositories under test.
        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s?options=search_path%%3Dchat",
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        // Flyway — blocking JDBC side-channel that runs the chat migrations.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // Redis — reactive hot cache.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.password", () -> "");
    }
}
