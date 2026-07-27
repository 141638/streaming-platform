package com.streaming.stream.api;

import com.streaming.stream.security.StreamAuthorization.StreamAccessDeniedException;
import com.streaming.stream.service.StreamService.InvalidPublishTokenException;
import com.streaming.stream.service.StreamService.NoPublishKeyException;
import com.streaming.stream.service.StreamService.ProfileOwnershipException;
import com.streaming.stream.service.StreamService.StreamAlreadyLiveException;
import com.streaming.stream.service.StreamService.StreamConflictException;
import com.streaming.stream.service.StreamService.StreamNotFoundException;
import com.streaming.stream.service.StreamService.StreamNotLiveException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class StreamExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamExceptionHandler.class);

    @ExceptionHandler(StreamNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(StreamNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "STREAM_NOT_FOUND", "message", ex.getMessage()));
    }

    @ExceptionHandler(StreamNotLiveException.class)
    public ResponseEntity<Map<String, String>> handleNotLive(StreamNotLiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "STREAM_NOT_LIVE", "message", ex.getMessage()));
    }

    @ExceptionHandler(StreamAlreadyLiveException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyLive(StreamAlreadyLiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "STREAM_ALREADY_LIVE", "message", ex.getMessage()));
    }

    @ExceptionHandler(StreamConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(StreamConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "STREAM_CONFLICT", "message", ex.getMessage()));
    }

    @ExceptionHandler(NoPublishKeyException.class)
    public ResponseEntity<Map<String, String>> handleNoPublishKey(NoPublishKeyException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NO_PUBLISH_KEY", "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidPublishTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPublishToken(
            InvalidPublishTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "INVALID_PUBLISH_TOKEN", "message", ex.getMessage()));
    }

    @ExceptionHandler(ProfileOwnershipException.class)
    public ResponseEntity<Map<String, String>> handleProfileOwnership(
            ProfileOwnershipException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "PROFILE_OWNERSHIP", "message", ex.getMessage()));
    }

    @ExceptionHandler(StreamAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(
            StreamAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "ACCESS_DENIED", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "INVALID_STATE_TRANSITION", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("Unhandled exception in stream controller", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR",
                        "message", "An unexpected error occurred."));
    }
}
