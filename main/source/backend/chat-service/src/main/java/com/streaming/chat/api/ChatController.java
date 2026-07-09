package com.streaming.chat.api;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.api.dto.SendMessageRequest;
import com.streaming.chat.application.ChatService;
import com.streaming.common.api.ApiMessage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for chat operations.
 *
 * <p>The author identity is always extracted from the JWT, never from the
 * request body. The {@code authorSubject} comes from {@code sub};
 * {@code authorUsername} is a convenience denormalization from
 * {@code attr.username}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("chat-service", "ok"));
    }

    /**
     * Send a message to a chat room.
     *
     * <p>If the room does not exist yet, it is auto-created. The author is
     * derived from {@code jwt.subject} and {@code jwt.attr.username}, not
     * from the request body.
     */
    @PostMapping(path = "/rooms/{roomKey}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MessageResponse> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @Valid @RequestBody SendMessageRequest body
    ) {
        String authorSubject = jwt.getSubject();
        String authorUsername = JwtAttr.username(jwt);
        return chatService.sendMessage(roomKey, authorSubject, authorUsername, body.content());
    }

    /**
     * Get metadata about a chat room (status, creation time, etc.).
     * Returns 404 if the room does not exist yet.
     */
    @GetMapping("/rooms/{roomKey}")
    public Mono<RoomResponse> getRoom(
            @PathVariable String roomKey
    ) {
        return chatService.getRoom(roomKey);
    }

    /**
     * Get messages for a room. By default returns the most recent 50.
     * Pass {@code before} (ISO-8601 cursor) and {@code limit} for
     * cursor-based pagination when scrolling up through history.
     */
    @GetMapping("/rooms/{roomKey}/messages/recent")
    public Flux<MessageResponse> getRecentMessages(
            @PathVariable String roomKey,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (before != null && !before.isBlank()) {
            return chatService.getMessagesBefore(roomKey, before, limit)
                    .flatMapMany(Flux::fromIterable);
        }
        return chatService.getRecentMessages(roomKey)
                .flatMapMany(Flux::fromIterable);
    }

}
