package com.streaming.stream.api;

import com.streaming.stream.api.dto.CreateStreamKeyResponse;
import com.streaming.stream.messaging.StreamEventPublisher;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class StreamController {

    private final StreamEventPublisher streamEventPublisher;

    public StreamController(StreamEventPublisher streamEventPublisher) {
        this.streamEventPublisher = streamEventPublisher;
    }

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("stream-service", "ok"));
    }

    @PostMapping("/stream-keys")
    public Mono<CreateStreamKeyResponse> createStreamKey() {
        String streamId = UUID.randomUUID().toString();
        String streamKey = UUID.randomUUID().toString().replace("-", "");
        streamEventPublisher.publishStreamStarted(streamId, streamKey);
        return Mono.just(new CreateStreamKeyResponse(
                streamId,
                streamKey,
                "rtmp://${SRS_HOST:localhost}:${SRS_RTMP_PORT:1935}/live/",
                "http://${SRS_HOST:localhost}:${SRS_HTTP_PORT:8085}/live/"
        ));
    }

    public record ApiMessage(String service, String status) {
    }
}
