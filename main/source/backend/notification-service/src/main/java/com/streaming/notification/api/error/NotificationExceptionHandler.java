package com.streaming.notification.api.error;

import com.streaming.notification.application.NotificationService.NotificationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps notification domain exceptions to HTTP responses carrying a
 * {@link NotificationApiError} envelope.
 */
@RestControllerAdvice
public class NotificationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationExceptionHandler.class);

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<NotificationApiError> handleNotFound(NotificationNotFoundException ex) {
        log.debug("Notification not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationApiError("NOTIFICATION_NOT_FOUND", ex.getMessage()));
    }
}
