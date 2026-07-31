package com.streaming.notification.api.error;

import com.streaming.notification.application.NotificationService.NotificationNotFoundException;
import com.streaming.notification.application.PreferenceService.PreferenceNotFoundException;
import com.streaming.notification.application.SubscriptionService.SubscriptionAlreadyExistsException;
import com.streaming.notification.application.SubscriptionService.SubscriptionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

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

    @ExceptionHandler(SubscriptionNotFoundException.class)
    public ResponseEntity<NotificationApiError> handleSubscriptionNotFound(
            SubscriptionNotFoundException ex) {
        log.debug("Subscription not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationApiError("SUBSCRIPTION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(SubscriptionAlreadyExistsException.class)
    public ResponseEntity<NotificationApiError> handleSubscriptionAlreadyExists(
            SubscriptionAlreadyExistsException ex) {
        log.debug("Subscription already exists: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new NotificationApiError("SUBSCRIPTION_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(PreferenceNotFoundException.class)
    public ResponseEntity<NotificationApiError> handlePreferenceNotFound(
            PreferenceNotFoundException ex) {
        log.debug("Preference not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationApiError("PREFERENCE_NOT_FOUND", ex.getMessage()));
    }

    /**
     * Handles validation failures from {@code @Valid} on request bodies.
     * Returns a structured 400 with field-level error details.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<NotificationApiError> handleValidation(WebExchangeBindException ex) {
        String detail = ex.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.debug("Validation error: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new NotificationApiError("VALIDATION_ERROR", detail));
    }

    /**
     * Catch-all for unexpected exceptions — logs the full stack trace
     * server-side but returns a generic message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<NotificationApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new NotificationApiError("INTERNAL_ERROR",
                        "An unexpected error occurred"));
    }
}
