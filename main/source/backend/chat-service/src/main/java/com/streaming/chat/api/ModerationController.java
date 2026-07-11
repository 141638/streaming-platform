package com.streaming.chat.api;

import com.streaming.chat.api.dto.BanRequest;
import com.streaming.chat.api.dto.BanResponse;
import com.streaming.chat.application.ModerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for room moderation (bans).
 *
 * <p>All operations require the PBAC {@code chat:moderation moderate} authority
 * on the target room (enforced in {@link ModerationService} via
 * {@code ChatAuthorization}). The moderator identity is always taken from the
 * JWT {@code sub}, never from the request body.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ModerationController {

    private final ModerationService moderationService;

    /**
     * Ban a user from a room.
     */
    @PostMapping(path = "/rooms/{roomKey}/bans", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<BanResponse> banUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @Valid @RequestBody BanRequest body
    ) {
        return moderationService.ban(jwt, roomKey, body.bannedSubject(), body.reason());
    }

    /**
     * Lift a user's ban from a room.
     */
    @DeleteMapping("/rooms/{roomKey}/bans/{subject}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unbanUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey,
            @PathVariable String subject
    ) {
        return moderationService.unban(jwt, roomKey, subject);
    }

    /**
     * List all bans for a room.
     */
    @GetMapping("/rooms/{roomKey}/bans")
    public Flux<BanResponse> listBans(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String roomKey
    ) {
        return moderationService.listBans(jwt, roomKey);
    }
}
