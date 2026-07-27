package com.streaming.chat.api;

import com.streaming.pbac.JwtAttr;
import java.util.List;

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

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.api.dto.SendMessageRequest;
import com.streaming.chat.application.ChatService;
import com.streaming.common.api.ApiMessage;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for chat operations.
 *
 * <p>
 * The author identity is always extracted from the JWT, never from the
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
     * <p>
     * The room must already exist (created from a {@code STREAM_CREATED} Kafka
     * event) and be active — sending to a missing or archived room errors. The
     * author is derived from {@code jwt.subject} and {@code jwt.attr.username},
     * not from the request body.
     */
    @PostMapping(path = "/rooms/{roomKey}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MessageResponse> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @Valid @RequestBody SendMessageRequest body) {
        String authorSubject = jwt.getSubject();
        String authorUsername = JwtAttr.username(jwt);
        return chatService.sendMessage(jwt, roomKey, authorSubject, authorUsername, body.content());
    }

    /**
     * Get metadata about a chat room (status, creation time, etc.).
     * Returns 404 if the room does not exist yet.
     */
    @GetMapping("/rooms/{roomKey}")
    public Mono<RoomResponse> getRoom(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey) {
        return chatService.getRoom(jwt, roomKey);
    }

    /**
     * Get messages for a room. By default returns the most recent 50.
     * Pass {@code before} (ISO-8601 cursor) and {@code limit} for
     * cursor-based pagination when scrolling up through history.
     */
    @GetMapping("/rooms/{roomKey}/messages/recent")
    public Flux<MessageResponse> getRecentMessages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        if (before != null && !before.isBlank()) {
            return chatService.getMessagesBefore(jwt, roomKey, before, limit)
                    .flatMapMany(Flux::fromIterable);
        }
        return chatService.getRecentMessages(jwt, roomKey)
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * Find distinct author usernames for @mention autocomplete.
     * Returns anyone who has ever chatted in this room (not just the current
     * visible message list). Pass {@code q} for prefix filtering
     * (case-insensitive).
     */
    @GetMapping("/rooms/{roomKey}/participants")
    public Mono<List<String>> getParticipants(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "10") int limit) {
        return chatService.getParticipants(roomKey, q, limit);
    }

}
