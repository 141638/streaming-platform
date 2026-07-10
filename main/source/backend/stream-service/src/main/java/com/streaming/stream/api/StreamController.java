package com.streaming.stream.api;

import com.streaming.common.api.ApiMessage;
import com.streaming.stream.api.dto.CategoryResponse;
import com.streaming.stream.api.dto.ChannelResponse;
import com.streaming.stream.api.dto.CreateStreamRequest;
import com.streaming.stream.api.dto.PublishKeyResponse;

import com.streaming.stream.api.dto.StreamResponse;
import com.streaming.stream.api.dto.StreamSummaryResponse;
import com.streaming.stream.api.dto.UpdateProfileRequest;
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

    // ── Health ──────────────────────────────────────────────────────────────

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("stream-service", "ok"));
    }

    // ── Categories ──────────────────────────────────────────────────────────

    @GetMapping("/categories")
    public Mono<ResponseEntity<Flux<CategoryResponse>>> listCategories() {
        return Mono.just(ResponseEntity.ok(streamService.listCategories()));
    }

    // ── Streams CRUD ────────────────────────────────────────────────────────

    @PostMapping(path = "/streams", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<StreamResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStreamRequest body) {
        return streamService.createStream(body, jwt)
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
    public Mono<ResponseEntity<StreamResponse>> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.getStream(id, jwt).map(ResponseEntity::ok);
    }

    // ── Channel page (authenticated, cross-user read) ────────────────────────

    @GetMapping("/channels/{username}")
    public Mono<ResponseEntity<ChannelResponse>> getChannel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String username) {
        return streamService.getChannel(username).map(ResponseEntity::ok);
    }

    // ── Channel profile (owner-only write) ────────────────────────────────────

    @PostMapping(path = "/channels/{username}/profile",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String username,
            @Valid @RequestBody UpdateProfileRequest body) {
        return streamService.updateProfile(username, body, jwt)
                .then(Mono.just(ResponseEntity.ok().build()));
    }

    @PatchMapping(path = "/streams/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<StreamResponse>> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStreamRequest body) {
        return streamService.updateStream(id, body, jwt).map(ResponseEntity::ok);
    }

    @DeleteMapping("/streams/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.deleteStream(id, jwt)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @PostMapping("/streams/{id}/start")
    public Mono<ResponseEntity<StreamResponse>> start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.startStream(id, jwt).map(ResponseEntity::ok);
    }

    @PostMapping("/streams/{id}/end")
    public Mono<ResponseEntity<StreamResponse>> end(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.endStream(id, jwt).map(ResponseEntity::ok);
    }

    @PostMapping("/streams/{id}/cancel")
    public Mono<ResponseEntity<StreamResponse>> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.cancelStream(id, jwt).map(ResponseEntity::ok);
    }

    // ── Go-live from SCHEDULED ───────────────────────────────────────────────

    @PostMapping("/streams/{id}/go-live")
    public Mono<ResponseEntity<PublishKeyResponse>> goLive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.goLiveFromSchedule(id, jwt).map(ResponseEntity::ok);
    }

    // ── Publish Key ─────────────────────────────────────────────────────────

    @PostMapping("/streams/{id}/publish-key")
    public Mono<ResponseEntity<PublishKeyResponse>> issuePublishKey(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.issuePublishKey(id, jwt)
                .map(response -> ResponseEntity
                        .created(URI.create("/v1/streams/" + id + "/publish-key"))
                        .body(response));
    }

    @GetMapping("/streams/{id}/publish-key")
    public Mono<ResponseEntity<PublishKeyResponse>> getPublishKey(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return streamService.getPublishKey(id, jwt).map(ResponseEntity::ok);
    }
}
