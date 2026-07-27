package com.streaming.chat.application;

import com.streaming.chat.api.dto.MessageResponse;
import com.streaming.chat.api.dto.RoomResponse;
import com.streaming.chat.domain.ChatBan;
import com.streaming.chat.domain.ChatMessage;
import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.cache.RedisMessageCache;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatMessageRepository;
import com.streaming.chat.infrastructure.persistence.ReactiveChatRoomRepository;
import com.streaming.chat.security.ChatAuthorization;
import com.streaming.pbac.AuthAction;
import com.streaming.pbac.AuthResourceDomain;
import com.streaming.pbac.AuthResourceKind;
import com.streaming.pbac.RequiredAuthority;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Application service for chat message operations.
 *
 * <p>Owns the cache-aside orchestration: reads hit Redis first and fall back
 * to PostgreSQL; writes go to PostgreSQL first and then update Redis.
 *
 * <p>The write path is a small composed pipeline —
 * {@code lookup → active-check → guard → persist → cache} — so cross-cutting
 * concerns plug into explicit seams:
 * <ul>
 *   <li>{@link SendGuard} is the Layer-2 (resource-state) pre-write hook; Phase
 *       3.4 supplies the ban-enforcing implementation.</li>
 *   <li>The cache-write step self-heals a silently-dropped write by evicting the
 *       room key so the next read rebuilds from PG (ADR-0003 §evict-on-write-failure).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_RECENT = 50;

    /** Extracts @username mentions from message bodies. Must be preceded by whitespace or start-of-string. */
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<!\\w)@(\\w{1,32})");

    private final ReactiveChatRoomRepository roomRepository;
    private final ReactiveChatMessageRepository messageRepository;
    private final RedisMessageCache cache;
    private final SendGuard sendGuard;
    private final ChatAuthorization chatAuthorization;
    private final ReactiveChatBanRepository banRepository;

    /**
     * Send a message to a chat room.
     *
     * <p>The room must already exist (created from a {@code STREAM_CREATED} Kafka
     * event, Phase 3.3) and be active; otherwise the call errors with
     * {@link RoomNotFoundException} / {@link RoomArchivedException}. PBAC
     * {@code chat:message send} is enforced after the active check; the
     * {@link SendGuard} (Layer-2 ban check) runs after PBAC and before persistence.
     *
     * @param jwt the authenticated caller's validated access token
     * @param roomKey the room's external key
     * @param authorSubject the JWT {@code sub} claim — the authenticated user
     * @param authorUsername denormalized display name from JWT {@code attr.username}
     * @param body the message content
     * @return the sent message as a response DTO
     */
    public Mono<MessageResponse> sendMessage(
            Jwt jwt, String roomKey, String authorSubject, String authorUsername, String body) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .filter(ChatRoom::isActive)
                .switchIfEmpty(Mono.error(new RoomArchivedException(roomKey)))
                .flatMap(room -> chatAuthorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.CHAT, AuthResourceKind.MESSAGE,
                                AuthAction.SEND, room.getBroadcasterSubject()))
                        .thenReturn(room))
                .flatMap(room -> sendGuard.check(room, authorSubject).thenReturn(room))
                .flatMap(room -> persistAndCache(room, authorSubject, authorUsername, body, now));
    }

    /**
     * Post a system message to a chat room — automated announcements (stream
     * started, stream ended, etc.) that bypass JWT-based PBAC authorization and
     * the ban guard. The room must exist and be active.
     *
     * @param roomKey the room's external key
     * @param body    the system message content
     * @return the posted message as a response DTO
     */
    public Mono<MessageResponse> sendSystemMessage(String roomKey, String body) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .filter(ChatRoom::isActive)
                .switchIfEmpty(Mono.error(new RoomArchivedException(roomKey)))
                .flatMap(room -> {
                    ChatMessage msg = ChatMessage.createSystem(room.getId(), body, now);
                    return messageRepository.save(msg)
                            .map(saved -> MessageResponse.from(saved, room.getExternalKey()))
                            .flatMap(this::cacheWrite);
                });
    }

    private Mono<MessageResponse> persistAndCache(
            ChatRoom room, String authorSubject, String authorUsername, String body, OffsetDateTime now) {
        List<String> mentionList = parseMentions(body);
        String[] mentions = mentionList.toArray(new String[0]);
        ChatMessage msg = ChatMessage.create(room.getId(), authorSubject, authorUsername, body, now, mentions);
        // DEFERRED (Phase 6 — Notification Service):
        // For each username in `mentions`:
        //   1. Resolve user subject from auth-service or local denormalization
        //   2. Check SSE presence
        //   3. If online → push SSE notification
        //   4. If offline → queue email digest (batch window: 15 min)
        //   5. Email deep-link: /stream/{roomKey}?scrollTo={messageId}
        return messageRepository.save(msg)
                .map(saved -> MessageResponse.from(saved, room.getExternalKey()))
                .flatMap(this::cacheWrite);
    }

    /**
     * Extract @username mentions from the message body.
     * The backend is the authority on who got mentioned — the client
     * autocomplete is UX-only. Matches {@code @word} where word is 1–32
     * word characters preceded by whitespace or start-of-string.
     */
    static List<String> parseMentions(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        return MENTION_PATTERN.matcher(body).results()
                .map(r -> r.group(1))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Update the hot cache after a successful PG persist. If the cache write
     * silently fails while Redis is otherwise serving reads, the room key would
     * serve a permanent hole (missing this message) until TTL expiry — so we
     * evict the key, forcing the next read to miss and rebuild the complete set
     * from PG. Turns a silent staleness window into a single cold read.
     *
     * <p>See ADR-0003 §evict-on-write-failure.
     * TODO(3.x-deferred): periodic reconciliation sweep for hot rooms — see
     * ADR-0003 §Deferred (Option 2). Revisit if evict-on-failure proves insufficient.
     */
    private Mono<MessageResponse> cacheWrite(MessageResponse response) {
        return cache.addToRecent(response.roomKey(), response)
                .flatMap(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        log.debug("Message cached: roomKey={}, id={}", response.roomKey(), response.id());
                        return Mono.just(response);
                    }
                    log.warn("Cache write failed for roomKey={}, evicting key to force PG rebuild on next read",
                            response.roomKey());
                    return cache.evictRoom(response.roomKey()).thenReturn(response);
                });
    }

    /**
     * Get recent messages for a room.
     *
     * <p>Enforces PBAC {@code chat:message read} against the room owner, then
     * attempts Redis first and falls back to PostgreSQL on cache miss. Message
     * reads are unified under the {@code chat:message} kind ({@code read} for the
     * recent/hot path, {@code read_history} for durable cursor pagination);
     * {@code chat:room} is reserved for room-metadata lookups ({@link #getRoom}).
     * If the room does not exist the authorization step is a no-op and the cache
     * path yields an empty list (unchanged from Phase 0 behaviour).
     *
     * @param jwt the authenticated caller's validated access token
     * @param roomKey the room's external key
     * @return the most recent messages (up to {@value #MAX_RECENT})
     */
    public Mono<List<MessageResponse>> getRecentMessages(Jwt jwt, String roomKey) {
        return authorizeRead(jwt, roomKey, AuthResourceKind.MESSAGE, AuthAction.READ)
                .then(cache.getRecent(roomKey, MAX_RECENT)
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        log.debug("Cache hit for roomKey={}, count={}", roomKey, cached.size());
                        return Mono.just(cached);
                    }
                    log.debug("Cache miss for roomKey={}, falling back to PG", roomKey);
                    return roomRepository.findByExternalKey(roomKey)
                            .flatMapMany(room -> messageRepository
                                    .findByRoomIdOrderByCreatedAtDesc(room.getId())
                                    .take(MAX_RECENT)
                                    .map(msg -> MessageResponse.from(msg, room.getExternalKey())))
                            .collectList()
                            .flatMap(fromPg -> {
                                // async backfill — don't block the response
                                Flux.fromIterable(fromPg)
                                        .flatMap(m -> cache.addToRecent(roomKey, m))
                                        .subscribe(
                                                count -> {
                                                },
                                                err -> log.warn("Backfill cache write failed for roomKey={}", roomKey,
                                                        err)
                                        );
                                return Mono.just(fromPg);
                            });
                }));
    }

    /**
     * Look up a room by its external key.
     *
     * <p>Enforces PBAC {@code chat:room read} against the room owner, then
     * attaches two per-caller signals: a {@code viewerCanModerate} capability bit
     * (from the {@code ent} claim, independent of {@code chat.pbac.enabled}) and a
     * {@code viewerBanned} resource-state bit (an active ban for the caller in this
     * room). The latter lets the client disable the composer on room load without
     * a failed send, independently of the push pipeline. An unauthenticated caller
     * ({@code jwt == null}) yields {@code false} for both rather than erroring.
     *
     * @param jwt the authenticated caller's validated access token
     * @param roomKey the room's external key
     * @return the room metadata, or an error if not found
     */
    public Mono<RoomResponse> getRoom(Jwt jwt, String roomKey) {
        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .flatMap(room -> chatAuthorization
                        .requireAccess(jwt, new RequiredAuthority(
                                AuthResourceDomain.CHAT, AuthResourceKind.ROOM,
                                AuthAction.READ, room.getBroadcasterSubject()))
                        .thenReturn(room))
                .flatMap(room -> Mono.zip(
                                chatAuthorization.hasCapability(jwt, new RequiredAuthority(
                                        AuthResourceDomain.CHAT, AuthResourceKind.MODERATION,
                                        AuthAction.MODERATE, room.getBroadcasterSubject())),
                                resolveViewerBanned(jwt, room.getId()))
                        .map(signals -> RoomResponse.from(room, signals.getT1(), signals.getT2())));
    }

    /**
     * Resolve whether the caller currently has an <em>active</em> ban in the room.
     * A {@code null} principal (unauthenticated) is never banned. Reuses the same
     * {@code (room_id, banned_subject)} unique-indexed lookup as the send guard and
     * the entity's {@link ChatBan#isActive(OffsetDateTime)} rule, so the metadata
     * signal matches exactly what {@code BanSendGuard} enforces.
     */
    private Mono<Boolean> resolveViewerBanned(Jwt jwt, UUID roomId) {
        if (jwt == null) {
            return Mono.just(false);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return banRepository.findByRoomIdAndBannedSubject(roomId, jwt.getSubject())
                .map(ban -> ban.isActive(now))
                .defaultIfEmpty(false);
    }

    /**
     * Get messages older than the given cursor, for lazy-load history.
     *
     * <p>Enforces PBAC {@code chat:message read_history} against the room owner.
     *
     * @param jwt the authenticated caller's validated access token
     * @param roomKey the room's external key
     * @param cursor ISO-8601 timestamp of the oldest message currently loaded
     * @param limit max number of messages to return
     * @return messages ordered newest-first (empty list if none)
     */
    public Mono<List<MessageResponse>> getMessagesBefore(Jwt jwt, String roomKey, String cursor, int limit) {
        double maxScore = parseCursorToEpochMillis(cursor);
        return authorizeRead(jwt, roomKey, AuthResourceKind.MESSAGE, AuthAction.READ_HISTORY)
                .then(cache.getBefore(roomKey, maxScore, limit)
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        log.debug("Cache before-hit for roomKey={}, count={}", roomKey, cached.size());
                        return Mono.just(cached);
                    }
                    log.debug("Cache before-miss for roomKey={}, falling back to PG", roomKey);
                    return roomRepository.findByExternalKey(roomKey)
                            .flatMapMany(room -> messageRepository
                                    .findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                                            room.getId(),
                                            parseCursorToInstant(cursor))
                                    .take(limit)
                                    .map(msg -> MessageResponse.from(msg, room.getExternalKey())))
                            .collectList()
                            .flatMap(fromPg -> {
                                // async backfill — don't block the response
                                Flux.fromIterable(fromPg)
                                        .flatMap(m -> cache.addToRecent(roomKey, m))
                                        .subscribe(
                                                count -> {},
                                                err -> log.warn("Backfill cache write failed for roomKey={}", roomKey,
                                                        err)
                                        );
                                return Mono.just(fromPg);
                            });
                }));
    }

    /**
     * Enforce a read authority against the room owner without disturbing the
     * cache-first read pipeline. Loads the room only to resolve its
     * {@code broadcasterSubject}; when the room is absent this completes empty
     * (the downstream cache path then yields an empty list, preserving the
     * pre-PBAC read contract). When {@code chat.pbac.enabled=false}, the
     * {@link ChatAuthorization} short-circuit makes this a cheap no-op after the
     * lookup.
     */
    private Mono<Void> authorizeRead(Jwt jwt, String roomKey, AuthResourceKind kind, AuthAction action) {
        return roomRepository.findByExternalKey(roomKey)
                .flatMap(room -> chatAuthorization.requireAccess(jwt, new RequiredAuthority(
                        AuthResourceDomain.CHAT, kind, action, room.getBroadcasterSubject())))
                .then();
    }

    private static double parseCursorToEpochMillis(String cursor) {
        try {
            return Instant.parse(cursor).toEpochMilli();
        } catch (Exception e) {
            log.warn("Invalid cursor, falling back to now: {}", cursor);
            return System.currentTimeMillis();
        }
    }

    private static Instant parseCursorToInstant(String cursor) {
        try {
            return Instant.parse(cursor);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /**
     * Get distinct author usernames for a room, filtered by prefix query.
     * Used by the @mention autocomplete for Tier-2 participant discovery —
     * finds anyone who has ever chatted in this room, not just visible
     * messages.
     *
     * @param roomKey the room's external key
     * @param query   prefix filter (case-insensitive); empty returns top 10
     * @param limit   max results (default 10)
     */
    public Mono<List<String>> getParticipants(String roomKey, String query, int limit) {
        return roomRepository.findByExternalKey(roomKey)
                .switchIfEmpty(Mono.error(new RoomNotFoundException(roomKey)))
                .flatMapMany(room -> messageRepository
                        .findDistinctAuthorUsernamesByRoomId(room.getId(), query != null ? query : "", limit))
                .collectList();
    }

    // -- exceptions --------------------------------------------------------

    public static class RoomNotFoundException extends RuntimeException {
        public RoomNotFoundException(String roomKey) {
            super("Chat room is notfound: roomKey=" + roomKey);
        }
    }

    public static class RoomArchivedException extends RuntimeException {
        public RoomArchivedException(String roomKey) {
            super("Chat room is archived: roomKey=" + roomKey);
        }
    }
}
