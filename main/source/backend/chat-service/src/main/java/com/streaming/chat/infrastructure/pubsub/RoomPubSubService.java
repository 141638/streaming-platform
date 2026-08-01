package com.streaming.chat.infrastructure.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streaming.chat.api.ws.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Room-keyed WebSocket session registry backed by lazy Redis Pub/Sub
 * subscriptions.
 *
 * <p>Each room gets a single Redis subscription that fans out incoming
 * messages to every {@link WebSocketSession} currently registered for that
 * room. Subscriptions are created on first join and disposed when the last
 * session leaves — no idle subscriptions accumulate.
 */
@Component
@Slf4j
public class RoomPubSubService {

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final ReactiveRedisMessageListenerContainer container;

    private final ConcurrentHashMap<String, RoomSub> rooms = new ConcurrentHashMap<>();

    public RoomPubSubService(ReactiveRedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    /**
     * Registry entry for one room: the active sessions and the Redis
     * subscription that feeds them.
     */
    private record RoomSub(Set<WebSocketSession> sessions, Disposable redisSub) {}

    /**
     * Register a WebSocket session for a room, creating a Redis Pub/Sub
     * subscription on first join.
     *
     * @param roomKey the room's external key
     * @param session the WebSocket session to register
     */
    public void addSession(String roomKey, WebSocketSession session) {
        rooms.compute(roomKey, (key, existing) -> {
            if (existing != null) {
                existing.sessions().add(session);
                log.debug("Session joined existing room: roomKey={}, totalSessions={}",
                        roomKey, existing.sessions().size());
                return existing;
            }

            Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
            sessions.add(session);

            Disposable sub = container.receive(ChannelTopic.of(channelFor(roomKey)))
                    .flatMap(message -> {
                        WebSocketFrame.Message frame;
                        try {
                            frame = objectMapper.readValue(
                                    message.getMessage(), WebSocketFrame.Message.class);
                        } catch (Exception e) {
                            log.debug("Failed to deserialize pub/sub message for roomKey={}: {}",
                                    roomKey, e.getMessage());
                            return Mono.empty();
                        }
                        String json;
                        try {
                            json = objectMapper.writeValueAsString(frame);
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to serialize frame for broadcast, roomKey={}: {}",
                                    roomKey, e.getMessage());
                            return Mono.empty();
                        }
                        return Flux.fromIterable(List.copyOf(sessions))
                                .flatMap(ws -> ws.send(Mono.just(ws.textMessage(json)))
                                        .onErrorResume(ex -> {
                                            sessions.removeIf(w -> !w.isOpen());
                                            log.debug("Removed closed session from roomKey={}", roomKey);
                                            return Mono.empty();
                                        }))
                                .then();
                    })
                    .subscribe();

            log.info("Room subscription created: roomKey={}", roomKey);
            return new RoomSub(sessions, sub);
        });
    }

    /**
     * Remove a WebSocket session from a room. When the last session leaves,
     * the Redis subscription is disposed and the room entry is removed.
     *
     * @param roomKey the room's external key
     * @param session the WebSocket session to remove
     */
    public void removeSession(String roomKey, WebSocketSession session) {
        rooms.computeIfPresent(roomKey, (key, existing) -> {
            existing.sessions().remove(session);
            if (existing.sessions().isEmpty()) {
                existing.redisSub().dispose();
                log.info("Room subscription disposed (last session left): roomKey={}", roomKey);
                return null; // removes the entry
            }
            log.debug("Session removed from room: roomKey={}, remainingSessions={}",
                    roomKey, existing.sessions().size());
            return existing;
        });
    }

    /**
     * Publish a message frame to a room via Redis Pub/Sub. Failures are
     * logged and swallowed — publish is fire-and-forget.
     *
     * @param roomKey the room's external key
     * @param frame   the message frame to broadcast
     * @return a Mono that completes with the publish result, or 0 on error
     */
    public Mono<Long> publish(String roomKey, WebSocketFrame.Message frame) {
        String channel = channelFor(roomKey);
        String json;
        try {
            json = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize frame for publish, roomKey={}: {}",
                    roomKey, e.getMessage());
            return Mono.just(0L);
        }
        ByteBuffer channelBuffer = ByteBuffer.wrap(channel.getBytes(StandardCharsets.UTF_8));
        ByteBuffer messageBuffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        ReactiveRedisConnection conn = connectionFactory.getReactiveConnection();
        return conn.pubSubCommands()
                .publish(channelBuffer, messageBuffer)
                .doFinally(sig -> conn.close())
                .onErrorResume(ex -> {
                    log.warn("Publish failed for roomKey={}: {}", roomKey, ex.getMessage());
                    return Mono.just(0L);
                });
    }

    /**
     * Count active WebSocket sessions in a room.
     *
     * @param roomKey the room's external key
     * @return session count (0 if the room has no subscribers)
     */
    public int sessionCount(String roomKey) {
        RoomSub sub = rooms.get(roomKey);
        return sub != null ? sub.sessions().size() : 0;
    }

    /**
     * Sum of active sessions across all rooms.
     *
     * @return total session count
     */
    public int totalSessions() {
        return rooms.values().stream()
                .mapToInt(room -> room.sessions().size())
                .sum();
    }

    private static String channelFor(String roomKey) {
        return "chat:room:" + roomKey + ":messages";
    }
}
