package com.streaming.stream.api;

import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;
import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateStreamRequest;
import com.streaming.stream.service.StreamService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class StreamController {

    private final StreamService streamService;

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("stream-service", "ok"));
    }

    @PostMapping(path = "/streams", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<StreamResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStreamRequest body) {
        String sub = jwt.getSubject();
        return streamService.createStream(sub, body)
                .map(response -> ResponseEntity
                        .created(URI.create("/v1/streams/" + response.id()))
                        .body(response));
    }

    @GetMapping("/streams")
    public Mono<ResponseEntity<Flux<StreamSummaryResponse>>> listMine(
            @AuthenticationPrincipal Jwt jwt) {
        String sub = jwt.getSubject();
        return Mono.just(ResponseEntity.ok(streamService.listMyStreams(sub)));
    }

    @GetMapping("/streams/{id}")
    public Mono<ResponseEntity<StreamResponse>> get(@PathVariable UUID id) {
        return streamService.getStream(id)
                .map(ResponseEntity::ok);
    }

    @PatchMapping(path = "/streams/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<StreamResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStreamRequest body) {
        return streamService.updateStream(id, body)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/streams/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return streamService.deleteStream(id)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/streams/{id}/publish-key")
    public Mono<ResponseEntity<PublishKeyResponse>> issuePublishKey(@PathVariable UUID id) {
        return streamService.issuePublishKey(id)
                .map(response -> ResponseEntity
                        .created(URI.create("/v1/streams/" + id + "/publish-key"))
                        .body(response));
    }

    @GetMapping("/streams/{id}/publish-key")
    public Mono<ResponseEntity<PublishKeyResponse>> getPublishKey(@PathVariable UUID id) {
        return streamService.getPublishKey(id)
                .map(ResponseEntity::ok);
    }

    public record ApiMessage(String service, String status) {}
}
