package com.streaming.chat.application;

import com.streaming.chat.domain.ChatRoom;
import com.streaming.chat.infrastructure.persistence.ReactiveChatBanRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Layer-2 (resource-state) send guard that rejects senders with an active,
 * unexpired ban for the target room.
 *
 * <p>Because this is a {@code @Component} implementing {@link SendGuard}, the
 * Phase-0 no-op default ({@code GuardConfig#noOpSendGuard}, which is
 * {@code @ConditionalOnMissingBean}) automatically backs off — this becomes the
 * single {@link SendGuard} bean.
 *
 * <p>Ban lookup is PG-direct: the {@code (room_id, banned_subject)} query is
 * unique-indexed and hits the same warm connection as the surrounding write, so
 * cardinality is one row and latency is negligible.
 * // OPT(scale): Redis SET chat:ban:room:{key} when cardinality/rate warrants — PG indexed query suffices now.
 */
@Component
@RequiredArgsConstructor
public class BanSendGuard implements SendGuard {

    private final ReactiveChatBanRepository banRepository;

    @Override
    public Mono<Void> check(ChatRoom room, String authorSubject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return banRepository.findByRoomIdAndBannedSubject(room.getId(), authorSubject)
                .filter(ban -> ban.isActive(now))
                .flatMap(ban -> Mono.<Void>error(
                        new UserBannedException(room.getExternalKey(), authorSubject)));
    }

    // -- exception ---------------------------------------------------------

    /**
     * Thrown when a banned user attempts to send a message. Mapped to
     * {@code 403 CHAT_USER_BANNED} by {@code ChatExceptionHandler} — distinct
     * from the PBAC {@code 403 AUTHZ_DENIED}.
     */
    public static final class UserBannedException extends RuntimeException {
        public UserBannedException(String roomKey, String subject) {
            super("User is banned from chat room: roomKey=" + roomKey + ", subject=" + subject);
        }
    }
}
