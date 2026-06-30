package com.streaming.stream.messaging;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StreamEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StreamEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void publishStreamStarted(String streamId, String streamKey) {
        String payload =
                "{\"eventType\":\"STREAM_STARTED\",\"streamId\":\"%s\",\"streamKey\":\"%s\",\"eventId\":\"%s\"}"
                        .formatted(streamId, streamKey, UUID.randomUUID());
        kafkaTemplate.send("stream.control", streamId, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka publish failed: {}", ex.toString());
                    }
                });
    }
}
