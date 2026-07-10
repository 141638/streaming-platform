package com.streaming.chat.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.domain.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that exercise the Redis cache directly, with no Spring context.
 *
 * <p>Starts a single Redis container per class and builds a
 * {@link ReactiveStringRedisTemplate} by hand against it — this keeps the cache
 * unit-of-work tests fast and free of the Kafka/Eureka/PG wiring that a full
 * {@code @SpringBootTest} would drag in. The keyspace is flushed before each test
 * for isolation.
 */
@Testcontainers
public abstract class AbstractRedisIntegrationTest {

    protected static final String REDIS_IMAGE = "redis:7-alpine";
    protected static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofSeconds(60));

    protected LettuceConnectionFactory connectionFactory;
    protected ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void initRedisTemplate() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        flushAll();
    }

    @AfterEach
    void destroyRedisTemplate() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** Remove all keys so each test starts from a clean keyspace. */
    protected void flushAll() {
        redisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushAll()
                .block(Duration.ofSeconds(5));
    }

    // -- fixtures ----------------------------------------------------------

    /** Build a NORMAL text message with the given body and creation instant. */
    protected static MessageResponse message(String roomKey, String body, OffsetDateTime createdAt) {
        return new MessageResponse(
                UUID.randomUUID(),
                roomKey,
                "author-" + UUID.randomUUID(),
                "tester",
                null,
                body,
                MessageType.NORMAL.wireValue(),
                (BigDecimal) null,
                null,
                createdAt);
    }

    protected static OffsetDateTime utc(long epochMillisOffset) {
        return OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillisOffset), ZoneOffset.UTC);
    }
}
