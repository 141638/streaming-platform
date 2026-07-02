package com.streaming.chat.api;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.SendMessageRequest;
import com.streaming.chat.application.ChatService;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for chat operations.
 *
 * <p>The author identity is always extracted from the JWT {@code sub} claim,
 * never from the request body. This prevents author impersonation.
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
     * derived from {@code jwt.subject}, not from the request body.
     */
    @PostMapping(path = "/rooms/{roomKey}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MessageResponse> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @Valid @RequestBody SendMessageRequest body
    ) {
        String authorSubject = jwt.getSubject();
        return chatService.sendMessage(roomKey, authorSubject, body.content());
    }

    /**
     * Get the most recent messages for a room.
     */
    @GetMapping("/rooms/{roomKey}/messages/recent")
    public Flux<MessageResponse> getRecentMessages(
            @PathVariable String roomKey
    ) {
        return chatService.getRecentMessages(roomKey)
                .flatMapMany(Flux::fromIterable);
    }

    // -- inline types ------------------------------------------------------

    public record ApiMessage(String service, String status) {
    }
}
