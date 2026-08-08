package com.streaming.chat.application;

import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service for chat room lifecycle.
 *
 * <p>In the current scaffold, rooms are auto-created on first message.
 * Phase 3.3 will drive room creation from Kafka stream events instead.
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final ReactiveChatRoomRepository roomRepository;

    /**
     * Get a room by its external key.
     *
     * @param externalKey the stream session external key
     * @return the room, or {@link Mono#empty()} if not found
     */
    public Mono<ChatRoom> findByExternalKey(String externalKey) {
        return roomRepository.findByExternalKey(externalKey);
    }

    /**
     * Create a chat room for the given external key.
     *
     * <p>Idempotent — if a room with the key already exists, it is returned
     * as-is rather than failing. The {@code broadcasterSubject} and
     * {@code broadcasterUsername} are set at creation time and ignored on
     * subsequent calls (the first writer wins).
     *
     * @param externalKey the stream session external key
     * @param broadcasterSubject the streamer's JWT sub (from STREAM_CREATED event)
     * @param broadcasterUsername the streamer's display name (from STREAM_CREATED event;
     *                            nullable — pre-existing rooms or tokens without the
     *                            username claim)
     * @return the new or existing room
     */
    public Mono<ChatRoom> getOrCreate(String externalKey, String broadcasterSubject,
                                      String broadcasterUsername) {
        return roomRepository.findByExternalKey(externalKey)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Creating chat room for externalKey={} broadcasterSubject={} broadcasterUsername={}",
                            externalKey, broadcasterSubject, broadcasterUsername);
                    return roomRepository.save(
                            ChatRoom.create(externalKey, broadcasterSubject, broadcasterUsername,
                                    OffsetDateTime.now(ZoneOffset.UTC)));
                }));
    }

    /**
     * Archive a room (called when the associated stream ends).
     *
     * <p>Idempotent — archiving an already-archived room is a no-op.
     *
     * @param externalKey the stream session external key
     */
    public Mono<Void> archive(String externalKey) {
        return roomRepository.findByExternalKey(externalKey)
                .flatMap(room -> {
                    if (!room.isActive()) {
                        log.debug("Room already archived: externalKey={}", externalKey);
                        return Mono.<ChatRoom>just(room);
                    }
                    room.archive(OffsetDateTime.now(ZoneOffset.UTC));
                    return roomRepository.save(room);
                })
                .doOnSuccess(room -> log.info("Room archived: externalKey={}", externalKey))
                .then();
    }
}
