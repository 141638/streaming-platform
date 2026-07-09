package com.streaming.chat.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.streaming.chat.application.RoomService;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
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
 */
@Component
public class StreamControlListener {

    private static final Logger log = LoggerFactory.getLogger(StreamControlListener.class);

    private final RoomService roomService;
    private final RedisMessageCache cache;
    private final ObjectMapper objectMapper;

    public StreamControlListener(RoomService roomService, RedisMessageCache cache) {
        this.roomService = roomService;
        this.cache = cache;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
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
            if (event.isStreamCreated()) {
                roomService.getOrCreate(event.streamId(), event.broadcasterSubject())
                        .doOnSuccess(room -> log.info("Room ready for stream: roomKey={} status={}",
                                room.getExternalKey(), room.getStatus().wireValue()))
                        .doOnError(err -> log.error("Failed to create room for streamId={}: {}",
                                event.streamId(), err.getMessage()))
                        .subscribe();
            } else if (event.isStreamEnded()) {
                roomService.archive(event.streamId())
                        .doOnSuccess(v -> {
                            cache.evictRoom(event.streamId())
                                    .doOnSuccess(evicted -> log.info("Room archived + cache evicted: roomKey={}",
                                            event.streamId()))
                                    .subscribe();
                        })
                        .doOnError(err -> log.error("Failed to archive room for streamId={}: {}",
                                event.streamId(), err.getMessage()))
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
