package com.streaming.chat.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.chat.application.ChatService;
import com.streaming.chat.application.RoomService;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.common.messaging.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reactive Kafka consumer that drives chat-room lifecycle from stream events.
 *
 * <p>Listens to the {@code stream.control} topic (same topic used by
 * notification-service). Room creation on {@code STREAM_CREATED} and archival
 * on {@code STREAM_ENDED} are both idempotent — duplicate or out-of-order
 * events are safe.
 *
 * <p>Posts a system message to the room on stream start / stream end so
 * viewers see lifecycle announcements inline in chat.
 */
@Component
public class StreamControlListener {

    private static final Logger log = LoggerFactory.getLogger(StreamControlListener.class);

    private final RoomService roomService;
    private final RedisMessageCache cache;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public StreamControlListener(RoomService roomService, RedisMessageCache cache, ChatService chatService,
                                  ObjectMapper objectMapper) {
        this.roomService = roomService;
        this.cache = cache;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${STREAM_CONTROL_TOPIC:stream.control}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onStreamControl(String payload) {
        StreamEvent event;
        try {
            event = objectMapper.readValue(payload, StreamEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize stream event, skipping: {}", e.getMessage());
            return;
        }

        log.info("Received stream event: type={} streamId={}", event.eventType(), event.streamId());

        try {
            if ("STREAM_CREATED".equals(event.eventType())) {
                roomService.getOrCreate(event.streamId(), event.broadcasterSubject())
                        .doOnSuccess(room -> log.info("Room ready for stream: roomKey={} status={}",
                                room.getExternalKey(), room.getStatus().wireValue()))
                        .doOnError(err -> log.error("Failed to create room for streamId={}: {}",
                                event.streamId(), err.getMessage()))
                        .then(chatService.sendSystemMessage(event.streamId(), "Stream started"))
                        .doOnSuccess(msg -> log.info("System message posted for STREAM_CREATED: roomKey={}",
                                event.streamId()))
                        .subscribe();
            } else if ("STREAM_ENDED".equals(event.eventType())) {
                boolean autoArchive = Boolean.TRUE.equals(event.autoArchiveChat());
                int delay = event.chatArchiveDelayMinutes() != null
                        ? event.chatArchiveDelayMinutes() : 0;

                // Always post a "Stream ended" system message so viewers
                // and the broadcaster see the lifecycle announcement in chat,
                // regardless of the auto-archive preference.
                chatService.sendSystemMessage(event.streamId(), "Stream ended")
                        .doOnSuccess(msg -> log.info(
                                "System message posted for STREAM_ENDED: roomKey={}",
                                event.streamId()))
                        .subscribe();

                if (!autoArchive) {
                    log.info("STREAM_ENDED with autoArchiveChat=false — leaving room ACTIVE: roomKey={}",
                            event.streamId());
                    return;
                }

                if (delay == 0) {
                    // Archive immediately
                    roomService.archive(event.streamId())
                            .doOnSuccess(v -> {
                                cache.evictRoom(event.streamId())
                                        .doOnSuccess(evicted -> log.info(
                                                "Room archived + cache evicted: roomKey={}",
                                                event.streamId()))
                                        .subscribe();
                            })
                            .doOnError(err -> log.error("Failed to archive room for streamId={}: {}",
                                    event.streamId(), err.getMessage()))
                            .subscribe();
                } else {
                    // Delay > 0 — ChatArchiveScheduler will handle via CHAT_ARCHIVE_TRIGGERED
                    log.info("STREAM_ENDED with delay={}min — deferring archive to scheduler: roomKey={}",
                            delay, event.streamId());
                }
            } else if ("CHAT_ARCHIVE_TRIGGERED".equals(event.eventType())) {
                roomService.archive(event.streamId())
                        .doOnSuccess(v -> {
                            cache.evictRoom(event.streamId())
                                    .doOnSuccess(evicted -> log.info(
                                            "Room archived via scheduler + cache evicted: roomKey={}",
                                            event.streamId()))
                                    .subscribe();
                        })
                        .doOnError(err -> log.error("Failed to archive room for streamId={}: {}",
                                event.streamId(), err.getMessage()))
                        .then(chatService.sendSystemMessage(event.streamId(),
                                "Chat archived"))
                        .doOnSuccess(msg -> log.info(
                                "System message posted for CHAT_ARCHIVE_TRIGGERED: roomKey={}",
                                event.streamId()))
                        .subscribe();
            } else {
                log.debug("Ignoring stream event type={} (no chat lifecycle action)", event.eventType());
            }
        } catch (Exception e) {
            log.error("Unhandled error processing stream event type={} streamId={}: {}",
                    event.eventType(), event.streamId(), e.getMessage(), e);
        }
    }
}
