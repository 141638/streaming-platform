package com.streaming.chat.api.error;

import com.streaming.chat.application.ChatService.RoomArchivedException;
import com.streaming.chat.application.ChatService.RoomNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps chat domain exceptions to HTTP responses carrying a {@link ChatApiError}
 * envelope. Without this advice, domain exceptions surface as HTTP 500 — which
 * is both semantically wrong (a missing/archived room is a client condition,
 * not a server fault) and impossible for clients to branch on.
 *
 * <p><b>Extension point (Phase 3.4 / Track A):</b> add handlers here for
 * {@code UserBannedException} → {@code 403 CHAT_USER_BANNED} and the PBAC
 * {@code ChatAccessDeniedException} → {@code 403 AUTHZ_DENIED}. The distinct
 * {@code code} values are what keep those two 403s unambiguous.
 */
@RestControllerAdvice
public class ChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatExceptionHandler.class);

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ChatApiError> handleRoomNotFound(RoomNotFoundException ex) {
        log.debug("Room not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ChatApiError("CHAT_ROOM_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(RoomArchivedException.class)
    public ResponseEntity<ChatApiError> handleRoomArchived(RoomArchivedException ex) {
        log.debug("Room archived: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ChatApiError("CHAT_ROOM_ARCHIVED", ex.getMessage()));
    }
}
