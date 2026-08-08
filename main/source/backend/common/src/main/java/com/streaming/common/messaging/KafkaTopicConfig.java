package com.streaming.common.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declarative Kafka topic definitions shared across all services.
 *
 * <p>Spring Kafka's {@link org.springframework.kafka.core.KafkaAdmin} creates these
 * topics idempotently at application startup ({@code --if-not-exists} behavior).
 * Only the producer service (stream-service) needs {@code KafkaAdmin} on its classpath;
 * pure consumers get topic name constants and event schemas from this module without
 * needing topic-creation privileges.
 *
 * <p><b>Adding a new topic:</b> add a {@code @Bean NewTopic} method here. Services that
 * depend on {@code common} will pick it up automatically on next restart. No broker
 * restart required.
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * Primary topic for stream lifecycle events.
     * <ul>
     *   <li>Producer: stream-service (via {@code OutboxPoller})</li>
     *   <li>Consumers: chat-service ({@code chat-service} group), notification-service
     *       ({@code notification-service} group)</li>
     *   <li>Partition key: {@code streamId}</li>
     * </ul>
     */
    @Bean
    public NewTopic streamControlTopic() {
        return TopicBuilder.name("stream.control")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Moderation event topic for real-time ban/unban push notifications.
     * <ul>
     *   <li>Producer: chat-service (via {@code ModerationEventPublisher})</li>
     *   <li>Consumer: notification-service ({@code notification-service} group)</li>
     *   <li>Partition key: {@code subject} (banned user's JWT sub)</li>
     * </ul>
     */
    @Bean
    public NewTopic chatModerationTopic() {
        return TopicBuilder.name("chat.moderation")
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Dead-letter topic for poison-pill messages after 3 failed retries.
     * <ul>
     *   <li>Producers (to DLQ): chat-service, notification-service (via
     *       {@code DeadLetterPublishingRecoverer})</li>
     *   <li>Consumer: none currently — messages accumulate; alerting deferred</li>
     * </ul>
     */
    @Bean
    public NewTopic streamControlDlqTopic() {
        return TopicBuilder.name("stream.control.dlq")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
