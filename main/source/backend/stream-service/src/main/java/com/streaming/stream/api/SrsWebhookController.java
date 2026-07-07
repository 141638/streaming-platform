package com.streaming.stream.api;

import com.streaming.stream.api.dto.SrsWebhookPayload;
import com.streaming.stream.service.StreamService;
import com.streaming.stream.service.StreamService.InvalidPublishTokenException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Webhook endpoints called by SRS {@code http_hooks} on publish/unpublish events.
 *
 * <p>These endpoints are NOT protected by JWT auth — they use the publish
 * token embedded in the RTMP URL for authentication (validated by
 * {@link StreamService#handlePublish}). In Phase 2, both SRS and
 * stream-service run on the internal Docker network.
 *
 * <p>Paths are under {@code /v1/webhooks/srs/} and must be permitted in
 * {@code SecurityConfig}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1/webhooks/srs", produces = MediaType.APPLICATION_JSON_VALUE)
public class SrsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SrsWebhookController.class);

    private final StreamService streamService;

    /**
     * Called by SRS before accepting an RTMP connection.
     *
     * <p>SRS pauses RTMP acceptance until this endpoint responds.
     * 200 = accept, any other status = reject.
     */
    @PostMapping(path = "/on_publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> onPublish(@RequestBody SrsWebhookPayload body) {
        String token = body.extractToken();
        if (token == null) {
            log.warn("on_publish: missing token in param: {}", body.param());
            return Mono.just(ResponseEntity.status(403).build());
        }

        return streamService.handlePublish(body.stream(), token)
                .thenReturn(ResponseEntity.ok().<Void>build())
                .onErrorResume(InvalidPublishTokenException.class, e -> {
                    log.warn("on_publish rejected: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(403).build());
                })
                .onErrorResume(StreamService.StreamAlreadyLiveException.class, e -> {
                    log.warn("on_publish rejected: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(409).build());
                });
    }

    /** Called by SRS when an RTMP connection ends. */
    @PostMapping(path = "/on_unpublish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> onUnpublish(@RequestBody SrsWebhookPayload body) {
        return streamService.handleUnpublish(body.stream())
                .thenReturn(ResponseEntity.ok().<Void>build());
    }
}
