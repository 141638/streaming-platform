package com.streaming.chat.application;

import com.streaming.chat.domain.ChatRoom;
import reactor.core.publisher.Mono;

/**
 * Pre-write guard hook evaluated before a message is persisted.
 *
 * <p>This is the seam that keeps <b>Layer 2 (resource-state / ABAC)</b> checks —
 * most importantly per-room user bans — out of {@link ChatService}'s
 * cache/persistence orchestration. {@link ChatService#sendMessage} invokes the
 * guard after the room lookup + active check (Layer 1 capability enforcement via
 * PBAC lives at the controller/service boundary, above this).
 *
 * <p><b>Phase 0</b> wires a no-op default (see {@code GuardConfig}). <b>Phase 3.4
 * (Track A)</b> supplies {@code BanSendGuard}, which rejects senders with an
 * active, unexpired ban by erroring with {@code UserBannedException}
 * (→ {@code 403 CHAT_USER_BANNED}).
 */
public interface SendGuard {

    /**
     * @return {@link Mono#empty()} when the send is permitted; a {@link Mono}
     *         error otherwise (the error's type drives the HTTP response).
     */
    Mono<Void> check(ChatRoom room, String authorSubject);
}
