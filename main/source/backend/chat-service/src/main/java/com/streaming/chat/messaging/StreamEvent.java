package com.streaming.chat.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of the {@code stream.control} event envelope published by stream-service.
 *
 * <p>Only the fields chat-service needs are deserialized; unknown fields
 * (e.g., {@code eventId}, {@code timestamp}) are silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamEvent(
        @JsonProperty("eventType") String eventType,
        @JsonProperty("streamId") String streamId,
        @JsonProperty("broadcasterSubject") String broadcasterSubject,
        @JsonProperty("autoArchiveChat") Boolean autoArchiveChat,
        @JsonProperty("chatArchiveDelayMinutes") Integer chatArchiveDelayMinutes
) {
    public boolean isStreamCreated() {
        return "STREAM_CREATED".equals(eventType);
    }

    public boolean isStreamEnded() {
        return "STREAM_ENDED".equals(eventType);
    }

    public boolean isChatArchiveTriggered() {
        return "CHAT_ARCHIVE_TRIGGERED".equals(eventType);
    }
}
