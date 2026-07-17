package com.streaming.stream.service;

import com.streaming.common.messaging.StreamEvent;
import com.streaming.stream.persistence.entity.StreamSessionEntity;
import com.streaming.stream.persistence.entity.StreamStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic job that archives chat rooms for ended streams whose
 * auto-archive delay has elapsed.
 *
 * <p>Runs every 60 seconds. For each eligible stream, sets
 * {@code chat_archived_at} and publishes a Kafka event so
 * chat-service can archive the room.
 */
@Component
public class ChatArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatArchiveScheduler.class);

    private final DatabaseClient databaseClient;
    private final OutboxWriter outboxWriter;

    public ChatArchiveScheduler(DatabaseClient databaseClient, OutboxWriter outboxWriter) {
        this.databaseClient = databaseClient;
        this.outboxWriter = outboxWriter;
    }

    @Scheduled(fixedDelay = 60_000)
    public void archiveExpiredChats() {
        String sql = """
                SELECT * FROM stream.stream_session
                WHERE status = 'ENDED'
                  AND auto_archive_chat = TRUE
                  AND chat_archived_at IS NULL
                  AND ended_at + (chat_archive_delay_minutes || ' minutes')::INTERVAL < NOW()
                """;

        databaseClient.sql(sql)
                .map((row, meta) -> {
                    StreamSessionEntity entity = new StreamSessionEntity();
                    entity.setId(row.get("id", java.util.UUID.class));
                    entity.setBroadcasterSubject(row.get("broadcaster_subject", String.class));
                    return entity;
                })
                .all()
                .flatMap(entity -> {
                    return databaseClient.sql("""
                                    UPDATE stream.stream_session
                                    SET chat_archived_at = :now
                                    WHERE id = :id
                                    """)
                            .bind("now", OffsetDateTime.now(ZoneOffset.UTC))
                            .bind("id", entity.getId())
                            .fetch()
                            .rowsUpdated()
                            .flatMap(rows -> {
                                if (rows > 0) {
                                    log.info("Chat archive triggered for ended stream: id={}",
                                            entity.getId());
                                    return outboxWriter.write(StreamEvent.chatArchiveTriggered(
                                            entity.getId(),
                                            entity.getBroadcasterSubject(),
                                            entity.getBroadcasterUsername()));
                                }
                                return reactor.core.publisher.Mono.empty();
                            });
                })
                .doOnError(e -> log.warn("ChatArchiveScheduler error: {}", e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
