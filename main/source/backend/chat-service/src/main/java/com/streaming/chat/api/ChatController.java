package com.streaming.chat.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private static final int MAX_RECENT = 50;

    private final ReactiveStringRedisTemplate redis;

    public ChatController(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("chat-service", "ok"));
    }

    @PostMapping("/rooms/{roomId}/messages")
    public Mono<ChatMessageDto> postMessage(
            @PathVariable String roomId,
            @Valid @RequestBody PostMessageRequest request
    ) {
        String key = "chat:room:" + roomId;
        String json = "{\"roomId\":\"%s\",\"author\":\"%s\",\"body\":\"%s\",\"ts\":\"%s\"}"
                .formatted(roomId, request.author(), escape(request.body()), Instant.now().toString());
        return redis.opsForList()
                .leftPush(key, json)
                .thenReturn(new ChatMessageDto(roomId, request.author(), request.body(), Instant.now().toString()));
    }

    @GetMapping("/rooms/{roomId}/messages/recent")
    public Flux<String> recent(@PathVariable String roomId) {
        String key = "chat:room:" + roomId;
        return redis.opsForList().range(key, 0, MAX_RECENT - 1);
    }

    private static String escape(String body) {
        return body.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ApiMessage(String service, String status) {
    }

    public record PostMessageRequest(@NotBlank String author, @NotBlank String body) {
    }

    public record ChatMessageDto(String roomId, String author, String body, String timestamp) {
    }
}
