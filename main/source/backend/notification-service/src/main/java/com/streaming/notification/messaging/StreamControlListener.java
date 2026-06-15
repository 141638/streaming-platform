package com.streaming.notification.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class StreamControlListener {

    private static final Logger log = LoggerFactory.getLogger(StreamControlListener.class);

    @KafkaListener(
            topics = "${STREAM_CONTROL_TOPIC:stream.control}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onStreamControl(String payload) {
        log.info("Received stream.control event payload={}", payload);
    }
}
