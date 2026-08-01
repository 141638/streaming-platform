package com.streaming.chat.api.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.error.ChatApiError;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sealed type hierarchy for the WebSocket frame protocol.
 *
 * <p>Every frame carries a {@code "type"} discriminator used by Jackson
 * polymorphic deserialization. Clients and servers branch on this field to
 * route frames to the appropriate handler.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WebSocketFrame.Send.class, name = "send"),
        @JsonSubTypes.Type(value = WebSocketFrame.Message.class, name = "message"),
        @JsonSubTypes.Type(value = WebSocketFrame.Error.class, name = "error")
})
public sealed interface WebSocketFrame
        permits WebSocketFrame.Send, WebSocketFrame.Message, WebSocketFrame.Error {

    /**
     * A chat message the client wants to send to a room.
     * Direction: client to server.
     */
    record Send(String clientId, String content) implements WebSocketFrame {}

    /**
     * A chat message pushed from the server to every subscriber in a room.
     * Direction: server to client.
     *
     * <p>The {@code clientId} field is non-null only for the sender's echo —
     * the sending client receives its own message with its clientId attached
     * so it can correlate acknowledgements. All other subscribers receive
     * the frame with {@code clientId = null}.
     */
    record Message(
            UUID id,
            String roomKey,
            String authorSubject,
            String authorUsername,
            String authorAvatarUrl,
            String body,
            String messageType,
            BigDecimal giftAmount,
            String giftCurrency,
            OffsetDateTime createdAt,
            List<String> mentions,
            @Nullable String clientId
    ) implements WebSocketFrame {

        /**
         * Build a WebSocket message frame from a persisted response and an
         * optional sender-correlation clientId.
         *
         * @param response the persisted message response
         * @param clientId non-null only for the sender's own echo
         */
        public static Message from(MessageResponse response, @Nullable String clientId) {
            return new Message(
                    response.id(),
                    response.roomKey(),
                    response.authorSubject(),
                    response.authorUsername(),
                    response.authorAvatarUrl(),
                    response.body(),
                    response.messageType(),
                    response.giftAmount(),
                    response.giftCurrency(),
                    response.createdAt(),
                    response.mentions(),
                    clientId
            );
        }
    }

    /**
     * A server-to-sender error frame indicating that a send was rejected.
     * Direction: server to sender only.
     */
    record Error(String clientId, String code, String message) implements WebSocketFrame {

        /**
         * Build an error frame for a specific client from the API error envelope.
         */
        public static Error from(String clientId, ChatApiError apiError) {
            return new Error(clientId, apiError.code(), apiError.message());
        }
    }
}
