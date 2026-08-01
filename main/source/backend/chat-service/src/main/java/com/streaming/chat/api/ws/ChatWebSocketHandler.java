package com.streaming.chat.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.chat.application.BanSendGuard;
import com.streaming.chat.application.ChatService;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.infrastructure.pubsub.RoomPubSubService;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.pbac.AuthAction;
import com.streaming.pbac.AuthResourceDomain;
import com.streaming.pbac.AuthResourceKind;
import com.streaming.pbac.JwtAttr;
import com.streaming.pbac.RequiredAuthority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket handler for real-time chat messaging.
 *
 * <p>Lifecycle per connection:
 * <ol>
 *   <li>Extract {@code roomKey} from URI path segments</li>
 *   <li>Extract JWT from {@code access_token} query parameter</li>
 *   <li>Validate JWT with {@link ReactiveJwtDecoder}</li>
 *   <li>PBAC enforce {@code chat:message send} + {@code chat:message read}</li>
 *   <li>Push recent message history to the connecting client</li>
 *   <li>Register session with {@link RoomPubSubService} for live fan-out</li>
 *   <li>Process inbound {@code send} frames — persist, publish, echo</li>
 *   <li>On close: unregister session</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

    private final ChatService chatService;
    private final RoomPubSubService roomPubSubService;
    private final ReactiveJwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;
    private final ChatAuthorization chatAuthorization;
    private final ReactiveChatRoomRepository roomRepository;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // ── (a) extract roomKey from URI path ──
        String path = session.getHandshakeInfo().getUri().getPath();
        String[] segments = path.split("/");
        String roomKey = null;
        for (int i = 0; i < segments.length - 1; i++) {
            if ("rooms".equals(segments[i])) {
                roomKey = segments[i + 1];
                break;
            }
        }
        if (roomKey == null || roomKey.isBlank()) {
            return closeSession(session, 4000, "Missing roomKey in WebSocket path");
        }

        // ── (b) extract JWT from query parameter ──
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null || query.isBlank()) {
            return closeSession(session, 4001, "Missing access_token query parameter");
        }
        String token = null;
        for (String param : query.split("&")) {
            if (param.startsWith("access_token=")) {
                String raw = param.substring("access_token=".length());
                token = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                break;
            }
        }
        if (token == null || token.isBlank()) {
            return closeSession(session, 4001, "Missing access_token query parameter");
        }

        // ── (c) validate JWT ──
        String finalRoomKey = roomKey;
        String finalToken = token;
        return jwtDecoder.decode(finalToken)
                .onErrorResume(err -> {
                    log.debug("JWT decode failed for WS: {}", err.getMessage());
                    return closeSession(session, 4001, "Invalid access token").then(Mono.empty());
                })
                .flatMap(jwt -> {
                    // ── (d) extract identity ──
                    String authorSubject = jwt.getSubject();
                    String authorUsername = JwtAttr.username(jwt);

                    // ── (e) PBAC check ──
                    return roomRepository.findByExternalKey(finalRoomKey)
                            .switchIfEmpty(Mono.defer(() ->
                                    closeSession(session, 4004, "Room not found")
                                            .then(Mono.empty())))
                            .flatMap(room -> chatAuthorization
                                    .requireAccess(jwt, new RequiredAuthority(
                                            AuthResourceDomain.CHAT, AuthResourceKind.MESSAGE,
                                            AuthAction.SEND, room.getBroadcasterSubject()))
                                    .then(chatAuthorization.requireAccess(jwt, new RequiredAuthority(
                                            AuthResourceDomain.CHAT, AuthResourceKind.MESSAGE,
                                            AuthAction.READ, room.getBroadcasterSubject())))
                                    .thenReturn(room)
                                    .onErrorResume(err -> {
                                        log.debug("PBAC denied for WS room={}: {}", finalRoomKey, err.getMessage());
                                        return closeSession(session, 4003, "Access denied").then(Mono.empty());
                                    }))
                            // ── (f) send history ──
                            .flatMap(room -> chatService.getRecentMessages(jwt, finalRoomKey)
                                    .flatMapMany(Flux::fromIterable)
                                    .map(response -> WebSocketFrame.Message.from(response, null))
                                    .flatMap(frame -> {
                                        try {
                                            return Mono.just(objectMapper.writeValueAsString(frame));
                                        } catch (Exception e) {
                                            return Mono.empty();
                                        }
                                    })
                                    .collectList()
                                    .flatMapMany(historyJsons -> Flux.fromIterable(historyJsons)
                                            .flatMap(json -> session.send(Mono.just(session.textMessage(json)))))
                                    .then()
                                    .thenReturn(room))
                            // ── (g) set up live stream ──
                            .flatMap(room -> {
                                session.getAttributes().put("jwt", jwt);
                                session.getAttributes().put("roomKey", finalRoomKey);
                                session.getAttributes().put("authorSubject", authorSubject);
                                session.getAttributes().put("authorUsername", authorUsername);
                                roomPubSubService.addSession(finalRoomKey, session);
                                log.debug("WS session joined: roomKey={}, subject={}", finalRoomKey, authorSubject);

                                // ── (h) process inbound frames ──
                                return session.receive()
                                        .map(WebSocketMessage::getPayloadAsText)
                                        .flatMap(payload -> {
                                            WebSocketFrame frame;
                                            try {
                                                frame = objectMapper.readValue(payload, WebSocketFrame.class);
                                            } catch (Exception e) {
                                                return Mono.empty();
                                            }

                                            if (frame instanceof WebSocketFrame.Send send) {
                                                return chatService.sendMessage(
                                                                jwt, finalRoomKey, authorSubject,
                                                                authorUsername, send.content())
                                                        .flatMap(response -> {
                                                            WebSocketFrame.Message msgFrame =
                                                                    WebSocketFrame.Message.from(response, null);
                                                            return roomPubSubService.publish(finalRoomKey, msgFrame)
                                                                    .thenReturn(response);
                                                        })
                                                        .flatMap(response -> {
                                                            WebSocketFrame.Message echo =
                                                                    WebSocketFrame.Message.from(
                                                                            response, send.clientId());
                                                            try {
                                                                String json = objectMapper.writeValueAsString(echo);
                                                                return session.send(
                                                                        Mono.just(session.textMessage(json)));
                                                            } catch (Exception e) {
                                                                return Mono.empty();
                                                            }
                                                        })
                                                        .onErrorResume(err -> {
                                                            String code;
                                                            if (err instanceof ChatService.RoomNotFoundException) {
                                                                code = "CHAT_ROOM_NOT_FOUND";
                                                            } else if (err instanceof ChatService.RoomArchivedException) {
                                                                code = "CHAT_ROOM_ARCHIVED";
                                                            } else if (err instanceof BanSendGuard.UserBannedException) {
                                                                code = "CHAT_USER_BANNED";
                                                            } else if (err instanceof ChatAuthorization.ChatAccessDeniedException) {
                                                                code = "AUTHZ_DENIED";
                                                            } else {
                                                                log.warn("Unexpected send error", err);
                                                                code = "INTERNAL_ERROR";
                                                            }
                                                            try {
                                                                String errorJson = objectMapper.writeValueAsString(
                                                                        new WebSocketFrame.Error(
                                                                                send.clientId(), code,
                                                                                err.getMessage()));
                                                                return session.send(
                                                                        Mono.just(session.textMessage(errorJson)));
                                                            } catch (Exception e) {
                                                                return Mono.empty();
                                                            }
                                                        })
                                                        .then();
                                            }
                                            return Mono.empty();
                                        })
                                        .doFinally(signalType -> {
                                            roomPubSubService.removeSession(finalRoomKey, session);
                                            log.debug("WS session closed: roomKey={}, signal={}",
                                                    finalRoomKey, signalType);
                                        })
                                        .then();
                            });
                });
    }

    private Mono<Void> closeSession(WebSocketSession session, int code, String reason) {
        return session.close(CloseStatus.create(code, reason));
    }
}
